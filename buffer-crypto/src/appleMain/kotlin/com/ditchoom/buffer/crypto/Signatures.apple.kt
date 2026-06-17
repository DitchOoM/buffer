@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CFBridgingRelease
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
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

private fun ecdsaAlgorithm(scheme: SignatureScheme): CFTypeRef? =
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

/** Builds the SecKey attribute dictionary {keyType: EC, keyClass: <class>}; caller [CFRelease]s. */
private fun keyAttributes(isPrivate: Boolean): CFDictionaryRef {
    val dict = NSMutableDictionary()
    dict.setObject(kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyType as Any)
    dict.setObject(
        if (isPrivate) kSecAttrKeyClassPrivate else kSecAttrKeyClassPublic,
        kSecAttrKeyClass as Any,
    )
    @Suppress("UNCHECKED_CAST")
    return CFBridgingRetain(dict) as CFDictionaryRef
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

private fun appleEcdsaVerify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    if (key.scheme == SignatureScheme.Ed25519) unsupportedEd25519()
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
