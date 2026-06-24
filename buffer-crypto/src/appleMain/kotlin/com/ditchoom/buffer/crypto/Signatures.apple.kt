@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_ecdsa_sign_from_scalar
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_ed25519_sign
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_ed25519_verify
import com.ditchoom.buffer.toNSData
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecKeyAlgorithm
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA384
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA512
import platform.posix.size_tVar

/**
 * Apple signature bridge over **Security.framework** (`SecKey*`).
 *
 * **ECDSA** P-256/384/521 are wired via `SecKeyCreateSignature` / `SecKeyVerifySignature` with the
 * `kSecKeyAlgorithmECDSASignatureMessageX962SHA{256,384,512}` algorithms, which hash-then-sign and
 * produce/consume **DER/X9.62** signatures — so [ecdsaSignatureEncoding] is `Der`, matching JVM.
 *
 * **Ed25519** is not exposed by the Security.framework C API — it lives in CryptoKit. It is wired
 * through the `cryptokitshim` cinterop ([bcks_ed25519_sign] / [bcks_ed25519_verify]), which calls
 * CryptoKit `Curve25519.Signing`. The Ed25519 witness is therefore [SignatureSupport.Blocking] on Apple.
 *
 * **ECDSA signing-from-scalar (Apple-specific):** `SecKeyCreateWithData` for an EC *private* key
 * requires the full ANSI X9.63 representation `04 ‖ X ‖ Y ‖ K`, not the bare scalar — it cannot
 * derive the public point. CryptoKit's `P###.Signing.PrivateKey(rawRepresentation:)` *can* build a
 * signing key from the bare scalar, so ECDSA signing routes through the CryptoKit shim
 * ([bcks_ecdsa_sign_from_scalar]) and [supportsEcdsaSigningFromScalar] is `true`. ECDSA *verify*
 * stays on Security.framework (`SecKeyVerifySignature`), which consumes the uncompressed point
 * `04 ‖ X ‖ Y` and DER signatures directly.
 */

actual val ecdsaSignatureEncoding: EcdsaSignatureEncoding get() = EcdsaSignatureEncoding.Der

/**
 * Every scheme has a synchronous path on Apple: ECDSA via Security.framework + the CryptoKit
 * sign-from-scalar shim, Ed25519 via the CryptoKit `Curve25519.Signing` shim.
 */
actual fun CryptoCapabilities.signatures(scheme: SignatureScheme): SignatureSupport = SignatureSupport.Blocking(SignatureBlockingOpsImpl)

private const val P256_CURVE_BITS = 256
private const val P384_CURVE_BITS = 384
private const val P521_CURVE_BITS = 521
private const val ED25519_SIGNATURE_BYTES = 64

/** Curve order in bits for the CryptoKit ECDSA-from-scalar shim (256 / 384 / 521). */
private fun ecdsaCurveCode(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> P256_CURVE_BITS
        SignatureScheme.EcdsaP384 -> P384_CURVE_BITS
        SignatureScheme.EcdsaP521 -> P521_CURVE_BITS
        SignatureScheme.Ed25519 -> error("not an ECDSA scheme")
    }

private fun ecdsaAlgorithm(scheme: SignatureScheme): SecKeyAlgorithm? =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
        SignatureScheme.EcdsaP384 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA384
        SignatureScheme.EcdsaP521 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA512
        SignatureScheme.Ed25519 -> error("Ed25519 does not use a Security.framework ECDSA algorithm")
    }

/** Snapshot the remaining bytes of [buffer] into an NSData (independent copy). */
private fun ReadBuffer.toNsData(): NSData {
    val start = position()
    val n = remaining()
    val bytes = ByteArray(n)
    for (i in 0 until n) bytes[i] = get(start + i)
    return bytes.toNSData()
}

/**
 * Builds the SecKey attribute dictionary {keyType: EC, keyClass: <class>}; caller [CFRelease]s.
 * Uses the CoreFoundation dictionary primitives directly (the known-good idiom in
 * [KeyAgreement.apple.kt]) rather than NSMutableDictionary + ObjC-ARC bridging, which does not
 * resolve cleanly through Kotlin/Native CF interop.
 */
private fun keyAttributes(isPrivate: Boolean): CFMutableDictionaryRef {
    val dict =
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
    CFDictionarySetValue(dict, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(
        dict,
        kSecAttrKeyClass,
        if (isPrivate) kSecAttrKeyClassPrivate else kSecAttrKeyClassPublic,
    )
    return dict
}

private fun createKey(
    data: NSData,
    isPrivate: Boolean,
): SecKeyRef? {
    val attrs = keyAttributes(isPrivate)

    @Suppress("UNCHECKED_CAST")
    val cfData = CFBridgingRetain(data) as CFDataRef
    return try {
        memScoped {
            val err = alloc<CFErrorRefVar>()
            SecKeyCreateWithData(cfData, attrs, err.ptr)
        }
    } finally {
        CFRelease(cfData)
        CFRelease(attrs)
    }
}

/**
 * Signs [message] under [key] through the CryptoKit shim and returns the signature bytes.
 *
 * Both schemes go through CryptoKit: Ed25519 via `Curve25519.Signing`, and ECDSA via
 * `P###.Signing.PrivateKey(rawRepresentation:)`, which (unlike Security.framework) accepts the bare
 * private scalar the [SigningKey] holds. ECDSA emits DER; Ed25519 the canonical 64-byte form.
 */
private fun cryptoKitSign(
    key: SigningKey,
    message: ReadBuffer,
): ByteArray {
    val cap = maxSignatureBytes(key.scheme)
    val scalar = key.requireInMemoryMaterial()
    return memScoped {
        val sigOut = allocArray<ByteVar>(cap)
        val sigLen = alloc<size_tVar>()
        var status = -1
        val msgLen = message.remaining()
        scalar.withRemainingBytes { keyPtr, keyLen ->
            // withRemainingBytes2 tolerates an empty message (Ed25519 KAT signs ""); it pins a
            // 1-byte placeholder and we still pass length 0 to the shim.
            message.withRemainingBytes2(msgLen) { msgPtr ->
                status =
                    if (key.scheme == SignatureScheme.Ed25519) {
                        bcks_ed25519_sign(
                            keyPtr.reinterpret(),
                            keyLen.convert(),
                            msgPtr.reinterpret(),
                            msgLen.convert(),
                            sigOut.reinterpret(),
                            cap.convert(),
                            sigLen.ptr,
                        )
                    } else {
                        bcks_ecdsa_sign_from_scalar(
                            ecdsaCurveCode(key.scheme),
                            keyPtr.reinterpret(),
                            keyLen.convert(),
                            msgPtr.reinterpret(),
                            msgLen.convert(),
                            sigOut.reinterpret(),
                            cap.convert(),
                            sigLen.ptr,
                        )
                    }
            }
        }
        require(status == BCKS_OK) { "invalid ${key.scheme.schemeName} private key" }
        val n = sigLen.value.toInt()
        ByteArray(n) { sigOut[it] }
    }
}

/** Verifies an Ed25519 signature through the CryptoKit shim. */
private fun appleEd25519Verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    // Ed25519 signatures are a fixed 64 bytes; reject anything else without calling into CryptoKit.
    if (signature.remaining() != ED25519_SIGNATURE_BYTES) return false
    var status = -1
    val msgLen = message.remaining()
    key.requireInMemoryMaterial().withRemainingBytes { pubPtr, pubLen ->
        message.withRemainingBytes2(msgLen) { msgPtr ->
            signature.withRemainingBytes { sigPtr, sigLen ->
                status =
                    bcks_ed25519_verify(
                        pubPtr.reinterpret(),
                        pubLen.convert(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigPtr.reinterpret(),
                        sigLen.convert(),
                    )
            }
        }
    }
    return status == BCKS_OK
}

private const val BYTE_MASK = 0xFF
private const val DER_LONG_FORM_FLAG = 0x80 // high bit set ⇒ long-form length / negative integer
private const val DER_LENGTH_COUNT_MASK = 0x7f // low 7 bits of a long-form first octet = octet count
private const val DER_MAX_LENGTH_OCTETS = 4 // we accept at most a 4-byte length
private const val DER_TAG_INTEGER = 0x02
private const val DER_TAG_SEQUENCE = 0x30

/**
 * Returns (decoded length, index just past the length octets) for a minimal-form DER length at
 * [i], or `null` if the length is non-canonical (indefinite form, leading-zero octet, or long
 * form used where short form would suffice).
 */
private fun readDerLen(
    b: ByteArray,
    i: Int,
): Pair<Int, Int>? {
    if (i >= b.size) return null
    val first = b[i].toInt() and BYTE_MASK
    if (first < DER_LONG_FORM_FLAG) return first to (i + 1) // short form
    val numBytes = first and DER_LENGTH_COUNT_MASK
    if (numBytes == 0 || numBytes > DER_MAX_LENGTH_OCTETS) return null // indefinite form / unreasonably large
    if (i + 1 + numBytes > b.size) return null
    if ((b[i + 1].toInt() and BYTE_MASK) == 0x00) return null // leading-zero length octet (non-minimal)
    var len = 0
    for (k in 0 until numBytes) len = (len shl Byte.SIZE_BITS) or (b[i + 1 + k].toInt() and BYTE_MASK)
    if (len < DER_LONG_FORM_FLAG) return null // long form used where short form fits (non-minimal)
    return len to (i + 1 + numBytes)
}

/**
 * Validates a canonical positive DER `INTEGER` starting at [i] inside `[i, end)` and returns the
 * index just past it, or `-1` if it is missing, negative, or carries a superfluous leading zero.
 */
private fun readDerPositiveInt(
    b: ByteArray,
    i: Int,
    end: Int,
): Int {
    if (i >= end || (b[i].toInt() and BYTE_MASK) != DER_TAG_INTEGER) return -1
    val (len, contentStart) = readDerLen(b, i + 1) ?: return -1
    if (len < 1 || contentStart + len > end) return -1
    val c0 = b[contentStart].toInt() and BYTE_MASK
    if (c0 and DER_LONG_FORM_FLAG != 0) return -1 // negative (r/s must be positive)
    if (c0 == 0x00 && len > 1 && (b[contentStart + 1].toInt() and DER_LONG_FORM_FLAG) == 0) {
        return -1 // superfluous 0x00
    }
    return contentStart + len
}

/**
 * Strict canonical-DER well-formedness check for an ECDSA `SEQUENCE { INTEGER r, INTEGER s }`.
 *
 * Apple's `SecKeyVerifySignature` is lenient — it accepts BER encodings (long-form or leading-zero
 * length octets, superfluous integer padding, trailing bytes) that strict DER, and therefore the
 * cross-platform contract and the Wycheproof `invalid` vectors, require be rejected. JCA on the JVM
 * rejects these for free; on Apple we enforce canonical DER ourselves before delegating to the
 * platform verify. Returns `false` for any non-canonical or malformed encoding.
 */
private fun isCanonicalEcdsaDer(signature: ReadBuffer): Boolean {
    val start = signature.position()
    val n = signature.remaining()
    if (n < 2) return false
    val b = ByteArray(n) { signature.get(start + it) }
    if ((b[0].toInt() and BYTE_MASK) != DER_TAG_SEQUENCE) return false
    val (seqLen, contentStart) = readDerLen(b, 1) ?: return false
    if (contentStart + seqLen != n) return false // exact consumption, no trailing bytes
    val afterR = readDerPositiveInt(b, contentStart, n)
    if (afterR < 0) return false
    val afterS = readDerPositiveInt(b, afterR, n)
    return afterS == n // r and s fill the sequence exactly
}

private fun appleEcdsaVerify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    // SecKeyVerifySignature accepts non-canonical BER; reject anything that is not strict DER first.
    if (!isCanonicalEcdsaDer(signature)) return false
    val pub = createKey(key.requireInMemoryMaterial().toNsData(), isPrivate = false) ?: return false

    @Suppress("UNCHECKED_CAST")
    val msgData = CFBridgingRetain(message.toNsData()) as CFDataRef

    @Suppress("UNCHECKED_CAST")
    val sigData = CFBridgingRetain(signature.toNsData()) as CFDataRef
    return try {
        memScoped {
            val err = alloc<CFErrorRefVar>()
            SecKeyVerifySignature(pub, ecdsaAlgorithm(key.scheme), msgData, sigData, err.ptr)
        }
    } finally {
        CFRelease(msgData)
        CFRelease(sigData)
        CFRelease(pub)
    }
}

internal actual fun signIntoPlatform(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int {
    val sig = cryptoKitSign(key, message)
    val n = sig.size
    require(dest.remaining() >= n) { "dest needs $n bytes remaining, has ${dest.remaining()}" }
    dest.writeBytes(sig)
    return n
}

internal actual fun verifyPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean =
    if (key.scheme == SignatureScheme.Ed25519) {
        appleEd25519Verify(key, message, signature)
    } else {
        appleEcdsaVerify(key, message, signature)
    }

internal actual suspend fun signAsyncPlatform(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val sig = cryptoKitSign(key, message)
    val out = factory.allocate(sig.size)
    out.writeBytes(sig)
    out.resetForRead()
    return out
}

internal actual suspend fun verifyAsyncPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = verifyPlatform(key, message, signature)

actual suspend fun ed25519AsyncAvailable(): Boolean = true

actual val supportsEcdsaSigningFromScalar: Boolean get() = true
