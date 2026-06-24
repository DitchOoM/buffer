@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_x25519_agree
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_x25519_generate
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_x25519_public_key
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
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
import platform.posix.size_tVar

/**
 * Apple key agreement.
 *
 * **ECDH P-256/384/521** is implemented against the Security framework (`SecKey*` /
 * `SecKeyCopyKeyExchangeResult` with `kSecKeyAlgorithmECDHKeyExchangeStandard`), which operates in
 * the pinned raw-point encoding (uncompressed `0x04 ‖ X ‖ Y`) natively. Off-curve / infinity /
 * small-subgroup peer points are rejected by `SecKeyCreateWithData` / the exchange, surfaced as
 * [InvalidPublicKey]. This file is verified on Mac CI — the Linux dev box does not compile Apple.
 *
 * **X25519** has no Security-framework key type — Curve25519 key agreement lives only in CryptoKit.
 * It is wired through the `cryptokitshim` cinterop ([bcks_x25519_generate] / [bcks_x25519_public_key]
 * / [bcks_x25519_agree]), which calls CryptoKit `Curve25519.KeyAgreement`. Keys use the RFC 7748 raw
 * encoding (32-byte little-endian scalar / u-coordinate) directly, matching the cross-platform
 * contract. X25519's [keyAgreement] witness is therefore [KeyAgreementSupport.Blocking]. The RFC 7748
 * §6.1 all-zero rejection is applied by the shared [validateRawSecret] post-check (the
 * `deriveSharedSecret` path runs it via the KDF).
 *
 * EC private keys are stored as the Security framework external representation (`0x04‖X‖Y‖scalar`) in
 * a wiped [SecureBuffer]; that encoding round-trips through `SecKeyCreateWithData` for the agreement.
 */

/** Whether [curve] has a synchronous native KA on Apple. Every supported curve does (X25519 via
 *  CryptoKit, the NIST P-curves via Security.framework). */
private fun appleSupportsSync(curve: KeyAgreementCurve): Boolean =
    when (curve) {
        KeyAgreementCurve.X25519 -> true
        KeyAgreementCurve.P256 -> true
        KeyAgreementCurve.P384 -> true
        KeyAgreementCurve.P521 -> true
    }

/** Apple has a synchronous native KA for every curve; else [Unavailable]. */
actual fun CryptoCapabilities.keyAgreement(curve: KeyAgreementCurve): KeyAgreementSupport =
    if (appleSupportsSync(curve)) {
        KeyAgreementSupport.Blocking(KeyAgreementBlockingOpsImpl(curve))
    } else {
        KeyAgreementSupport.Unavailable
    }

private const val P256_KEY_BITS = 256
private const val P384_KEY_BITS = 384
private const val P521_KEY_BITS = 521

private fun keySizeBits(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.P256 -> P256_KEY_BITS
        KeyAgreementCurve.P384 -> P384_KEY_BITS
        KeyAgreementCurve.P521 -> P521_KEY_BITS
        KeyAgreementCurve.X25519 -> error("X25519 unsupported on Apple")
    }

private fun requireSupported(curve: KeyAgreementCurve) {
    if (!appleSupportsSync(curve)) {
        throw UnsupportedOperationException("${curve.curveName} key agreement is not available on this platform")
    }
}

private fun requireEc(curve: KeyAgreementCurve) {
    if (curve == KeyAgreementCurve.X25519 || !appleSupportsSync(curve)) {
        throw UnsupportedOperationException("${curve.curveName} EC key agreement is not available on this platform")
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

// Each Security-framework null result is a distinct failure point mapped to InvalidPublicKey while
// CFRelease cleanup runs in try/finally; the multiple throws are inherent to that resource pattern.
@Suppress("ThrowsCount")
internal actual fun generateKeyPairPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    requireSupported(curve)
    if (curve == KeyAgreementCurve.X25519) return generateX25519KeyPair()
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
                val publicKey = KeyAgreementPublicKey.of(curve, rawPubBuf)
                val privBuf = cfDataToBuffer(privData, secureScratch)
                return keyAgreementKeyPairOf(curve, keyAgreementPrivateKeyOf(curve, privBuf), publicKey)
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

internal actual fun deriveSharedSecretPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    val curve = privateKey.curve
    val raw = rawAgreeApple(privateKey, peerPublicKey)
    return deriveFromRawSecret(curve, raw, info, length, salt, factory)
}

/**
 * Raw DH secret seam for HPKE/DHKEM (no KDF). Delegates to the same Security-framework exchange
 * as [deriveSharedSecret] and applies the audited [validateRawSecret] post-check.
 */
internal actual suspend fun dhRawSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer = validateRawSecret(privateKey.curve, rawAgreeApple(privateKey, peerPublicKey))

/**
 * Computes the raw ECDH secret into a wiped SecureBuffer; throws [InvalidPublicKey] on a bad point.
 *
 * Each Security-framework null result maps to InvalidPublicKey while CFRelease cleanup runs in
 * try/finally; the multiple throws are inherent to that resource-management pattern.
 */
@Suppress("ThrowsCount")
private fun rawAgreeApple(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    if (curve == KeyAgreementCurve.X25519) return rawAgreeX25519(privateKey, peerPublicKey)
    requireEc(curve)

    val privData = privateKey.requireInMemoryMaterial().toNsData()
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
                return cfDataToBuffer(sharedData, secureScratch)
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

// =============================================================================
// X25519 via the CryptoKit shim (Curve25519.KeyAgreement)
// =============================================================================

private const val X25519_BYTES = 32

/** Generates an X25519 key pair through CryptoKit; private scalar lands in a wiped SecureBuffer. */
private fun generateX25519KeyPair(): KeyAgreementKeyPair {
    memScoped {
        val privOut = allocArray<ByteVar>(X25519_BYTES)
        val pubOut = allocArray<ByteVar>(X25519_BYTES)
        val privLen = alloc<size_tVar>()
        val pubLen = alloc<size_tVar>()
        val status =
            bcks_x25519_generate(
                privOut.reinterpret(),
                X25519_BYTES.convert(),
                privLen.ptr,
                pubOut.reinterpret(),
                X25519_BYTES.convert(),
                pubLen.ptr,
            )
        check(status == BCKS_OK) { "X25519 key generation failed (status=$status)" }
        val privBuf = secureScratch.allocate(X25519_BYTES)
        for (i in 0 until X25519_BYTES) privBuf.writeByte(privOut[i])
        privBuf.resetForRead()
        val pubBuf = BufferFactory.Default.allocate(X25519_BYTES)
        for (i in 0 until X25519_BYTES) pubBuf.writeByte(pubOut[i])
        pubBuf.resetForRead()
        return keyAgreementKeyPairOf(
            KeyAgreementCurve.X25519,
            keyAgreementPrivateKeyOf(KeyAgreementCurve.X25519, privBuf),
            KeyAgreementPublicKey.of(KeyAgreementCurve.X25519, pubBuf),
        )
    }
}

/**
 * Raw X25519 agreement through CryptoKit into a wiped SecureBuffer. CryptoKit validates the peer
 * point and rejects malformed keys (surfaced as [InvalidPublicKey]); the shared
 * [validateRawSecret] / [deriveFromRawSecret] post-check enforces the RFC 7748 §6.1 all-zero
 * rejection for low-order points.
 */
private fun rawAgreeX25519(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val out = secureScratch.allocate(X25519_BYTES)
    memScoped {
        val secretOut = allocArray<ByteVar>(X25519_BYTES)
        val secretLen = alloc<size_tVar>()
        var status = -1
        privateKey.requireInMemoryMaterial().withRemainingBytes { privPtr, privLen ->
            peerPublicKey.encoded.withRemainingBytes { peerPtr, peerLen ->
                status =
                    bcks_x25519_agree(
                        privPtr.reinterpret(),
                        privLen.convert(),
                        peerPtr.reinterpret(),
                        peerLen.convert(),
                        secretOut.reinterpret(),
                        X25519_BYTES.convert(),
                        secretLen.ptr,
                    )
            }
        }
        if (status != BCKS_OK) {
            out.freeNativeMemory()
            throw InvalidPublicKey(KeyAgreementCurve.X25519.curveName)
        }
        for (i in 0 until X25519_BYTES) out.writeByte(secretOut[i])
    }
    out.resetForRead()
    return out
}

internal actual suspend fun generateKeyPairAsyncPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair =
    generateKeyPairPlatform(curve)

internal actual suspend fun deriveSharedSecretAsyncPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = deriveSharedSecretPlatform(privateKey, peerPublicKey, info, length, salt, factory)
