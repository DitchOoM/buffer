@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecKeyAlgorithm
import platform.Security.SecKeyCreateSignature
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
import platform.posix.memcpy

/**
 * Apple signature bridge over **Security.framework** (`SecKey*`).
 *
 * **ECDSA** P-256/384/521 are wired via `SecKeyCreateSignature` / `SecKeyVerifySignature` with the
 * `kSecKeyAlgorithmECDSASignatureMessageX962SHA{256,384,512}` algorithms, which hash-then-sign and
 * produce/consume **DER/X9.62** signatures — so [ecdsaSignatureEncoding] is `Der`, matching JVM.
 *
 * **Ed25519** is **not** exposed by the Security.framework C API (it lives in CryptoKit, which is
 * Swift-only and not reachable from this module's Kotlin/Native cinterop). Rather than ship a
 * Swift bridge that can't be built/verified here, Ed25519 is reported **unsupported** on Apple:
 * [supportsSyncEd25519] is `false` and every Ed25519 entry point throws
 * [UnsupportedOperationException] — the honest "native-or-throw" contract. (Documented deviation
 * from the original plan, which assumed a CryptoKit bridge.)
 *
 * **Key import note (Apple-specific):** `SecKeyCreateWithData` for an EC *private* key requires the
 * full ANSI X9.63 representation `04 ‖ X ‖ Y ‖ K` (public point concatenated with the private
 * scalar), not the bare scalar. So on Apple a [SigningKey] for ECDSA must be built from that full
 * representation. *Verify* keys are the ordinary uncompressed point `04 ‖ X ‖ Y`.
 */

actual val supportsSyncEd25519: Boolean get() = false

actual val supportsSyncEcdsa: Boolean get() = true

actual val ecdsaSignatureEncoding: EcdsaSignatureEncoding get() = EcdsaSignatureEncoding.Der

private fun unsupportedEd25519(): Nothing =
    throw UnsupportedOperationException(
        "Ed25519 is unavailable on Apple via Security.framework (CryptoKit bridge not wired)",
    )

private fun ecdsaAlgorithm(scheme: SignatureScheme): SecKeyAlgorithm? =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
        SignatureScheme.EcdsaP384 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA384
        SignatureScheme.EcdsaP521 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA512
        SignatureScheme.Ed25519 -> unsupportedEd25519()
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

private fun appleEcdsaSign(
    key: SigningKey,
    message: ReadBuffer,
): NSData {
    if (key.scheme == SignatureScheme.Ed25519) unsupportedEd25519()
    val priv =
        createKey(key.requireOpen().toNsData(), isPrivate = true)
            ?: throw IllegalArgumentException("invalid ${key.scheme.schemeName} private key")

    @Suppress("UNCHECKED_CAST")
    val msgData = CFBridgingRetain(message.toNsData()) as CFDataRef
    return try {
        memScoped {
            val err = alloc<CFErrorRefVar>()
            val sig =
                SecKeyCreateSignature(priv, ecdsaAlgorithm(key.scheme), msgData, err.ptr)
                    ?: throw IllegalArgumentException("ECDSA signing failed")
            CFBridgingRelease(sig) as NSData
        }
    } finally {
        CFRelease(msgData)
        CFRelease(priv)
    }
}

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
    val first = b[i].toInt() and 0xFF
    if (first < 0x80) return first to (i + 1) // short form
    val numBytes = first and 0x7f
    if (numBytes == 0 || numBytes > 4) return null // indefinite form / unreasonably large
    if (i + 1 + numBytes > b.size) return null
    if ((b[i + 1].toInt() and 0xFF) == 0x00) return null // leading-zero length octet (non-minimal)
    var len = 0
    for (k in 0 until numBytes) len = (len shl 8) or (b[i + 1 + k].toInt() and 0xFF)
    if (len < 0x80) return null // long form used where short form fits (non-minimal)
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
    if (i >= end || (b[i].toInt() and 0xFF) != 0x02) return -1
    val (len, contentStart) = readDerLen(b, i + 1) ?: return -1
    if (len < 1 || contentStart + len > end) return -1
    val c0 = b[contentStart].toInt() and 0xFF
    if (c0 and 0x80 != 0) return -1 // negative (r/s must be positive)
    if (c0 == 0x00 && len > 1 && (b[contentStart + 1].toInt() and 0x80) == 0) return -1 // superfluous 0x00
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
    if ((b[0].toInt() and 0xFF) != 0x30) return false
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
    if (key.scheme == SignatureScheme.Ed25519) unsupportedEd25519()
    // SecKeyVerifySignature accepts non-canonical BER; reject anything that is not strict DER first.
    if (!isCanonicalEcdsaDer(signature)) return false
    val pub = createKey(key.material.toNsData(), isPrivate = false) ?: return false

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

/** Copies [n] bytes from [data] into [dest] at its current position, advancing it. */
private fun copyNsDataInto(
    data: NSData,
    dest: WriteBuffer,
    n: Int,
) {
    if (n == 0) return
    val bytes = ByteArray(n)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, n.convert())
    }
    dest.writeBytes(bytes)
}

private fun NSData.toReadBuffer(factory: BufferFactory): PlatformBuffer {
    val n = length.toInt()
    val out = factory.allocate(n)
    copyNsDataInto(this, out, n)
    out.resetForRead()
    return out
}

actual fun signInto(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int {
    if (key.scheme == SignatureScheme.Ed25519) unsupportedEd25519()
    val sig = appleEcdsaSign(key, message)
    val n = sig.length.toInt()
    require(dest.remaining() >= n) { "dest needs $n bytes remaining, has ${dest.remaining()}" }
    copyNsDataInto(sig, dest, n)
    return n
}

actual fun verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = appleEcdsaVerify(key, message, signature)

actual suspend fun signAsync(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    if (key.scheme == SignatureScheme.Ed25519) unsupportedEd25519()
    return appleEcdsaSign(key, message).toReadBuffer(factory)
}

actual suspend fun verifyAsync(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = appleEcdsaVerify(key, message, signature)

actual suspend fun ed25519AsyncAvailable(): Boolean = false

actual val supportsEcdsaSigningFromScalar: Boolean get() = false
