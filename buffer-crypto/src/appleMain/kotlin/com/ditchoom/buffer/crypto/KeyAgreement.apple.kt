@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard

/**
 * Apple key agreement.
 *
 * **ECDH P-256/384/521** is implemented against the Security framework (`SecKey*` /
 * `SecKeyCopyKeyExchangeResult` with `kSecKeyAlgorithmECDHKeyExchangeStandard`), which operates in
 * the pinned raw-point encoding (uncompressed `0x04 ‖ X ‖ Y`) natively. Off-curve / infinity /
 * small-subgroup peer points are rejected by `SecKeyCreateWithData` / the exchange, surfaced as
 * [InvalidPublicKey]. This file is verified on Mac CI — the Linux dev box does not compile Apple.
 *
 * **X25519** has no Security-framework key type — Curve25519 key agreement lives only in CryptoKit,
 * which is Swift-only and unreachable through Kotlin/Native cinterop without a Swift shim. It is
 * therefore reported unsupported here ([supportsSyncX25519] = `false`) and throws; adding it needs a
 * CryptoKit bridge (tracked as a follow-up — see the PR notes).
 *
 * Private keys are stored as the Security framework external representation (`0x04‖X‖Y‖scalar`) in a
 * wiped [SecureBuffer]; that encoding round-trips through `SecKeyCreateWithData` for the agreement.
 */

actual val supportsSyncX25519: Boolean = false
actual val supportsSyncEcdhP256: Boolean = true
actual val supportsSyncEcdhP384: Boolean = true
actual val supportsSyncEcdhP521: Boolean = true

private fun keySizeBits(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.P256 -> 256
        KeyAgreementCurve.P384 -> 384
        KeyAgreementCurve.P521 -> 521
        KeyAgreementCurve.X25519 -> error("X25519 unsupported on Apple")
    }

private fun requireEc(curve: KeyAgreementCurve) {
    if (curve == KeyAgreementCurve.X25519 || !supportsSync(curve)) {
        throw UnsupportedOperationException("${curve.curveName} key agreement is not available on this platform")
    }
}

/** Copies a buffer's remaining bytes into an immutable [NSData] (Security APIs consume CFData/NSData). */
private fun ReadBuffer.toNsData(): NSData {
    if (remaining() == 0) return NSData()
    var result = NSData()
    withRemainingBytes { ptr, len -> result = NSData.dataWithBytes(ptr, len.convert()) }
    return result
}

/** Materializes a CFData into a read-ready buffer allocated from [factory] (no intermediate array). */
private fun cfDataToBuffer(
    data: CFDataRef,
    factory: BufferFactory,
): PlatformBuffer {
    val len = CFDataGetLength(data).toInt()
    val src = CFDataGetBytePtr(data)
    val out = factory.allocate(len)
    for (i in 0 until len) out.writeByte(src!![i].toByte())
    out.resetForRead()
    return out
}

/** Builds the `SecKey` attribute dictionary for [curve] with the given key class. Caller releases it. */
private fun attributes(
    curve: KeyAgreementCurve,
    keyClass: CFTypeRef?,
): CFMutableDictionaryRef {
    val dict =
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
    CFDictionarySetValue(dict, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(dict, kSecAttrKeyClass, keyClass)
    memScoped {
        val size = alloc<IntVar> { value = keySizeBits(curve) }
        val number = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, size.ptr)
        CFDictionarySetValue(dict, kSecAttrKeySizeInBits, number)
        number?.let { CFRelease(it) }
    }
    return dict
}

actual fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    requireEc(curve)
    val attrs = attributes(curve, kSecAttrKeyClassPrivate)
    val privKey =
        SecKeyCreateRandomKey(attrs, null) ?: run {
            CFRelease(attrs)
            throw InvalidPublicKey(curve.curveName)
        }
    CFRelease(attrs)
    try {
        val pubKey = SecKeyCopyPublicKey(privKey) ?: throw InvalidPublicKey(curve.curveName)
        try {
            val pubData = SecKeyCopyExternalRepresentation(pubKey, null) ?: throw InvalidPublicKey(curve.curveName)
            val privData = SecKeyCopyExternalRepresentation(privKey, null) ?: throw InvalidPublicKey(curve.curveName)
            try {
                val rawPubBuf = cfDataToBuffer(pubData, BufferFactory.Default)
                val publicKey = KeyAgreementPublicKey(curve, rawPubBuf)
                val privBuf = cfDataToBuffer(privData, secureScratch)
                return KeyAgreementKeyPair(curve, KeyAgreementPrivateKey(curve, privBuf), publicKey)
            } finally {
                CFRelease(pubData)
                CFRelease(privData)
            }
        } finally {
            CFRelease(pubKey)
        }
    } finally {
        CFRelease(privKey)
    }
}

actual fun deriveSharedSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    requireEc(curve)

    val privData = privateKey.encoded.toNsData()
    val pubData = peerPublicKey.encoded.toNsData()
    val privAttrs = attributes(curve, kSecAttrKeyClassPrivate)
    val pubAttrs = attributes(curve, kSecAttrKeyClassPublic)
    try {
        val secPriv =
            SecKeyCreateWithData(CFBridgingRetain(privData) as CFDataRef, privAttrs, null)
                ?: throw InvalidPublicKey(curve.curveName)
        val secPub =
            SecKeyCreateWithData(CFBridgingRetain(pubData) as CFDataRef, pubAttrs, null)
                ?: run {
                    CFRelease(secPriv)
                    throw InvalidPublicKey(curve.curveName)
                }
        try {
            val params =
                CFDictionaryCreateMutable(
                    kCFAllocatorDefault,
                    0,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                )!!
            val sharedData =
                SecKeyCopyKeyExchangeResult(
                    secPriv,
                    kSecKeyAlgorithmECDHKeyExchangeStandard,
                    secPub,
                    params,
                    null,
                ) ?: run {
                    CFRelease(params)
                    throw InvalidPublicKey(curve.curveName)
                }
            CFRelease(params)
            try {
                val raw = cfDataToBuffer(sharedData, secureScratch)
                return deriveFromRawSecret(curve, raw, info, length, salt, factory)
            } finally {
                CFRelease(sharedData)
            }
        } finally {
            CFRelease(secPriv)
            CFRelease(secPub)
        }
    } finally {
        CFRelease(privAttrs)
        CFRelease(pubAttrs)
    }
}

actual suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair = generateKeyPair(curve)

actual suspend fun deriveSharedSecretAsync(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = deriveSharedSecret(privateKey, peerPublicKey, info, length, salt, factory)
