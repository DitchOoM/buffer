@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed helper file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_AUTH
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_PEER
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_ka_agree
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_p256_ka_generate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.size_tVar

/*
 * Secure Enclave ECDH P-256 key agreement, plus the durable record framing both Enclave key kinds
 * share. Split from HardwareKeys.apple.kt, which retains the provider, the signing path, and the
 * shared generate plumbing.
 *
 * The custody model is the signing path's: the Enclave holds the private scalar and runs the
 * Diffie-Hellman *inside* the element ([bcks_secure_enclave_p256_ka_agree]); only the raw shared
 * secret crosses back, into a wiped [SecureBuffer] the common [validateRawSecret] / HKDF seam
 * consumes. So `keyAgreement()` / `hpke()` compose with an Enclave key unchanged, and the scalar
 * never enters process memory.
 *
 * Agreement keys are **advisory**-gated only. CryptoKit would accept a `SecAccessControl` on an
 * Enclave agreement key, but [UserAuthenticatedKeyProvider] exposes no key-agreement surface — an
 * OS-bound agreement key would have no caller and no test — so the gate here is the advisory
 * [ProtectedKeySpec.authorization], evaluated before every derive.
 */

/** Generates a P-256 key-agreement key inside the Secure Enclave (restore blob + public point). */
internal fun enclaveGenerateKaP256(): EnclaveP256Key =
    enclaveGenerate { blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen ->
        bcks_secure_enclave_p256_ka_generate(blobPtr, blobCap, blobLen, pointPtr, pointCap, pointLen)
    }

/**
 * Runs `DH(enclaveKey, peer)` inside the Enclave — the key restored from [blob], the peer supplied
 * as its uncompressed SEC1 point — and returns the raw 32-byte shared secret in a wiped
 * [SecureBuffer] (the common seam applies the KDF / validation above it).
 *
 * A rejected peer point (malformed, wrong length, off-curve) surfaces as the uniform
 * [InvalidPublicKey] with the shim's cause dropped — the same no-oracle contract as the software
 * agreement glue. A blob this Enclave can no longer restore is [HardwareKeyException.KeyInvalidated].
 */
internal fun enclaveAgreeP256(
    blob: ReadBuffer,
    peerPoint: ReadBuffer,
): PlatformBuffer {
    val cap = KeyAgreementCurve.P256.sharedSecretBytes
    val out = secureScratch.allocate(cap)
    var status = -1
    var written = 0
    memScoped {
        val secretLen = alloc<size_tVar>()
        val peerLen = peerPoint.remaining()
        blob.withRemainingBytes { blobPtr, blobLen ->
            peerPoint.withRemainingBytes2(peerLen) { peerPtr ->
                out.withWritablePointer(cap) { secretPtr ->
                    status =
                        bcks_secure_enclave_p256_ka_agree(
                            blobPtr.reinterpret(),
                            blobLen.convert(),
                            peerPtr.reinterpret(),
                            peerLen.convert(),
                            secretPtr.reinterpret(),
                            cap.convert(),
                            secretLen.ptr,
                        )
                }
            }
        }
        written = secretLen.value.toInt()
    }
    if (status != BCKS_OK) {
        out.freeNativeMemory()
        throw enclaveAgreeFailure(status)
    }
    out.position(written)
    out.resetForRead()
    return out
}

/**
 * The typed failure a non-OK agreement status stands for. Returned rather than thrown so the caller
 * frees the secret buffer first and keeps a single throw site.
 */
private fun enclaveAgreeFailure(status: Int): Throwable =
    when (status) {
        // Every peer-point rejection — malformed encoding, wrong length, off-curve — arrives as this
        // one code, so the exception carries no information about *which* check failed.
        BCKS_ERR_PEER -> InvalidPublicKey(KeyAgreementCurve.P256)
        // The Enclave cannot restore this blob (wrong device, or an OS-invalidated key).
        BCKS_ERR_INPUT -> HardwareKeyException.KeyInvalidated()
        // Unreachable today (no agreement key is access-controlled), kept typed rather than folded
        // into the generic hardware failure so an OS-bound key would surface honestly.
        BCKS_ERR_AUTH -> AuthorizationFailed()
        else -> HardwareKeyException.TransientHardwareFailure(retryable = false)
    }

// =============================================================================
// Durable Enclave key record — `u8 kind | u16 pointLen | point | blob`
// =============================================================================
//
// The public point and the Enclave restore blob together fully describe a persistent key (the point
// builds the VerifyKey / KeyAgreementPublicKey; the blob reconstructs the key inside the Enclave).
// They are framed into one opaque buffer the store persists verbatim in a Keychain item.
//
// The leading kind byte is what keeps signing and agreement keys from loading as each other: both
// are (point, blob) pairs, structurally indistinguishable without it. Records written before the
// agreement kind existed carry no kind byte and begin with the point length's high byte, which is
// always 0x00 for a 65-byte point — so a leading 0x00 identifies a legacy signing record and is
// parsed under the old layout.

/** The two persistent Enclave key kinds, tagged in the durable record's first byte. */
internal enum class EnclaveKeyKind(
    val tag: Int,
) {
    Signing(1),
    Agreement(2),
}

/** A parsed durable record: the key [kind] plus freshly-owned restore [blob] and public [point]. */
internal class EnclaveRecord(
    val kind: EnclaveKeyKind,
    val blob: PlatformBuffer,
    val point: PlatformBuffer,
)

/** Frames [kind] + [point] + [blob] into a durable record, without disturbing either source's position. */
internal fun frameEnclaveRecord(
    kind: EnclaveKeyKind,
    point: ReadBuffer,
    blob: ReadBuffer,
): PlatformBuffer {
    val pointLen = point.remaining()
    val out = BufferFactory.Default.allocate(RECORD_HEADER_BYTES + pointLen + blob.remaining())
    out.writeByte(kind.tag.toByte())
    out.writeShort(pointLen.toShort())
    appendPreservingPosition(out, point)
    appendPreservingPosition(out, blob)
    out.resetForRead()
    return out
}

/** Splits a durable record into its kind and freshly-owned (blob, point) buffers. */
internal fun parseEnclaveRecord(record: ReadBuffer): EnclaveRecord {
    val first = record.readByte().toInt() and BYTE_MASK
    val legacy = first == LEGACY_SIGNING_TAG
    // Legacy: `first` was the point length's high byte (0x00), so its low byte comes next.
    val pointLen =
        if (legacy) record.readByte().toInt() and BYTE_MASK else record.readShort().toInt() and SHORT_MASK
    val point = copyBuffer(record.readBytes(pointLen), BufferFactory.Default)
    val blob = copyBuffer(record.readBytes(record.remaining()), BufferFactory.Default)
    return EnclaveRecord(if (legacy) EnclaveKeyKind.Signing else enclaveKind(first), blob, point)
}

/**
 * The [ProtectedKeyAlgorithm] a stored record holds, read without consuming it — the store's
 * kind check before it hands the record to the matching re-attach path (a mismatch answers `null`
 * on a load and [KeyStoreException.AliasMismatch] on a get-or-generate).
 */
internal fun enclaveRecordAlgorithm(record: ReadBuffer): ProtectedKeyAlgorithm =
    when (record.get(record.position()).toInt() and BYTE_MASK) {
        LEGACY_SIGNING_TAG, EnclaveKeyKind.Signing.tag -> ProtectedKeyAlgorithm.EcdsaP256
        EnclaveKeyKind.Agreement.tag -> ProtectedKeyAlgorithm.EcdhP256
        else -> throw KeyStoreException.CorruptEntry()
    }

private fun enclaveKind(tag: Int): EnclaveKeyKind =
    when (tag) {
        EnclaveKeyKind.Signing.tag -> EnclaveKeyKind.Signing
        EnclaveKeyKind.Agreement.tag -> EnclaveKeyKind.Agreement
        else -> throw KeyStoreException.CorruptEntry()
    }

/** Appends [src]'s remaining bytes to [dst] without advancing [src]'s position (it may be a live key buffer). */
private fun appendPreservingPosition(
    dst: PlatformBuffer,
    src: ReadBuffer,
) {
    if (src.remaining() == 0) return
    val mark = src.position()
    dst.write(src)
    src.position(mark)
}

private const val RECORD_HEADER_BYTES = 1 + 2 // kind byte + u16 point length
private const val LEGACY_SIGNING_TAG = 0x00
private const val BYTE_MASK = 0xFF
private const val SHORT_MASK = 0xFFFF
