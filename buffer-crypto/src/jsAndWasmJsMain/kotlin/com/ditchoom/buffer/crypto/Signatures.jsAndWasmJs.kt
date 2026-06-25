package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * js/wasmJs signature support.
 *
 * WebCrypto's `SubtleCrypto.sign` / `.verify` are **async-only**, so every scheme's witness is
 * [SignatureSupport.AsyncOnly] — the synchronous ops are not members of it and cannot be reached.
 * The real work is in the async witness ops, which call WebCrypto through a per-engine bridge
 * (`dynamic` on JS, `@JsFun` externals on wasmJs).
 *
 * ECDSA signatures on WebCrypto are **raw P1363** (`r ‖ s`, fixed-width), pinned by
 * [ecdsaSignatureEncoding]; the Wycheproof suite exercises the `..._p1363_test.json` files here.
 *
 * Ed25519 is only present on newer engines (Chrome 137+/Firefox 129+/Safari 17+, Node stable). We
 * **feature-detect** it (see [webCryptoEd25519Available]); when absent every Ed25519 op throws
 * [UnsupportedOperationException], matching the capability contract.
 */

actual val ecdsaSignatureEncoding: EcdsaSignatureEncoding get() = EcdsaSignatureEncoding.P1363

/**
 * WebCrypto's sign/verify are `suspend`-only, so every scheme is [SignatureSupport.AsyncOnly] — the
 * synchronous ops are not reachable. Ed25519's actual engine support is feature-detected at call
 * time (see [ed25519AsyncAvailable]); the async ops throw [UnsupportedOperationException] if absent.
 */
actual fun CryptoCapabilities.signatures(scheme: SignatureScheme): SignatureSupport =
    SignatureSupport.AsyncOnly(SignatureAsyncOpsImpl(scheme))

internal actual fun signIntoPlatform(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int = throw UnsupportedOperationException("synchronous signing is unavailable on JS/WASM; use the async ops")

internal actual fun generateSigningKeyPlatform(
    scheme: SignatureScheme,
    factory: BufferFactory,
): SyncCapableSigningKey = throw UnsupportedOperationException("synchronous key generation is unavailable on JS/WASM; use the async ops")

internal actual fun verifyPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = throw UnsupportedOperationException("synchronous verification is unavailable on JS/WASM; use the async ops")

// ---------------------------------------------------------------------------
// Per-engine WebCrypto bridge (dynamic on JS, @JsFun on wasmJs).
// All material crosses the boundary as hex strings to dodge typed-array marshalling
// differences between the two backends — crypto inputs are small (keys/sigs/short msgs).
// ---------------------------------------------------------------------------

/** Feature-detect Ed25519 by attempting a key import; cached. `false` ⇒ Ed25519 unsupported. */
internal expect suspend fun webCryptoEd25519Available(): Boolean

/** WebCrypto sign. [privateMaterialHex] is PKCS#8 DER hex; returns the signature hex. */
internal expect suspend fun webCryptoSign(
    scheme: SignatureScheme,
    privateMaterialHex: String,
    messageHex: String,
): String

/** WebCrypto verify. [publicMaterialHex] is the raw/SPKI public key hex per scheme. */
internal expect suspend fun webCryptoVerify(
    scheme: SignatureScheme,
    publicMaterialHex: String,
    messageHex: String,
    signatureHex: String,
): Boolean

/**
 * WebCrypto key generation for [scheme]. Returns `"<privHex>:<pubHex>"` — the raw private material
 * (Ed25519 32-byte seed / ECDSA big-endian scalar) and the raw public key (Ed25519 32-byte point /
 * ECDSA uncompressed SEC1 point), both lowercase hex. Throws (engine rejection) for an unsupported
 * scheme (e.g. Ed25519 where the engine lacks it).
 */
internal expect suspend fun webCryptoGenerateKeyPair(scheme: SignatureScheme): String

// ---------------------------------------------------------------------------
// Hex <-> buffer helpers (no ByteArray staging beyond the WebCrypto string boundary).
// ---------------------------------------------------------------------------

private const val HEX = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8

// EC private-scalar / coordinate widths (bytes) per curve.
private const val P256_SCALAR_BYTES = 32
private const val P384_SCALAR_BYTES = 48
private const val P521_SCALAR_BYTES = 66
private const val ED25519_SEED_BYTES = 32

// DER definite-length encoding thresholds.
private const val DER_SHORT_LEN_LIMIT = 0x80 // lengths below use the single-byte short form
private const val DER_ONE_BYTE_LIMIT = 0x100 // lengths below fit in one long-form byte (0x81 LL)

private fun ReadBuffer.toHexString(): String {
    val start = position()
    val n = remaining()
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) {
        val v = get(start + i).toInt() and 0xFF
        sb.append(HEX[v ushr NIBBLE_BITS])
        sb.append(HEX[v and NIBBLE_MASK])
    }
    return sb.toString()
}

private fun hexToBuffer(
    hex: String,
    factory: BufferFactory,
): PlatformBuffer {
    val n = hex.length / 2
    val b = factory.allocate(n)
    for (i in 0 until n) {
        val hi = HEX.indexOf(hex[i * 2].lowercaseChar())
        val lo = HEX.indexOf(hex[i * 2 + 1].lowercaseChar())
        b.writeByte(((hi shl NIBBLE_BITS) or lo).toByte())
    }
    b.resetForRead()
    return b
}

// ---------------------------------------------------------------------------
// PKCS#8 assembly for WebCrypto private-key import, built through the buffer API.
// Ed25519 (RFC 8410) is the fixed prefix + 32-byte seed. ECDSA (RFC 5915 wrapped in
// PKCS#8) is assembled with a tiny DER writer.
// ---------------------------------------------------------------------------

private fun pkcs8Ed25519Hex(seed: ReadBuffer): String {
    require(seed.remaining() == ED25519_SEED_BYTES) { "Ed25519 seed must be 32 bytes" }
    return "302e020100300506032b657004220420" + seed.toHexString()
}

/** Curve byte length for the private scalar (also the coordinate width). */
private fun ecScalarBytes(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> P256_SCALAR_BYTES
        SignatureScheme.EcdsaP384 -> P384_SCALAR_BYTES
        SignatureScheme.EcdsaP521 -> P521_SCALAR_BYTES
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

/** Named-curve OID DER bytes (the OBJECT IDENTIFIER value, without tag/len). */
private fun curveOidHex(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "2a8648ce3d030107" // 1.2.840.10045.3.1.7
        SignatureScheme.EcdsaP384 -> "2b81040022" // 1.3.132.0.34
        SignatureScheme.EcdsaP521 -> "2b81040023" // 1.3.132.0.35
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

private fun derLenHex(len: Int): String =
    when {
        len < DER_SHORT_LEN_LIMIT -> twoHex(len)
        len < DER_ONE_BYTE_LIMIT -> "81" + twoHex(len)
        else -> "82" + twoHex(len ushr BYTE_BITS) + twoHex(len and BYTE_MASK)
    }

private fun twoHex(v: Int): String = HEX[(v ushr NIBBLE_BITS) and NIBBLE_MASK].toString() + HEX[v and NIBBLE_MASK]

private fun derTlv(
    tagHex: String,
    valueHex: String,
): String = tagHex + derLenHex(valueHex.length / 2) + valueHex

private fun pkcs8EcdsaHex(
    scheme: SignatureScheme,
    scalar: ReadBuffer,
): String {
    val sbytes = ecScalarBytes(scheme)
    require(scalar.remaining() == sbytes) { "${scheme.schemeName} scalar must be $sbytes bytes" }
    val dHex = scalar.toHexString()

    // ECPrivateKey ::= SEQUENCE { version INTEGER(1), privateKey OCTET STRING(d) }
    val ecPrivateKey =
        derTlv(
            "30",
            derTlv("02", "01") + derTlv("04", dHex),
        )

    // AlgorithmIdentifier ::= SEQUENCE { id-ecPublicKey, namedCurve OID }
    val algId =
        derTlv(
            "30",
            derTlv("06", "2a8648ce3d0201") + derTlv("06", curveOidHex(scheme)),
        )

    // PrivateKeyInfo ::= SEQUENCE { version INTEGER(0), AlgorithmIdentifier, OCTET STRING(ECPrivateKey) }
    return derTlv(
        "30",
        derTlv("02", "00") + algId + derTlv("04", ecPrivateKey),
    )
}

private fun privateMaterialHex(key: SigningKey): String {
    val material = key.requireInMemoryMaterial()
    return when (key.scheme) {
        SignatureScheme.Ed25519 -> pkcs8Ed25519Hex(material)
        else -> pkcs8EcdsaHex(key.scheme, material)
    }
}

// ---------------------------------------------------------------------------
// Suspending API
// ---------------------------------------------------------------------------

internal actual suspend fun signAsyncPlatform(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    if (key.scheme == SignatureScheme.Ed25519 && !webCryptoEd25519Available()) {
        throw UnsupportedOperationException("Ed25519 is not available on this WebCrypto engine")
    }
    val sigHex = webCryptoSign(key.scheme, privateMaterialHex(key), message.toHexString())
    return hexToBuffer(sigHex, factory)
}

internal actual suspend fun verifyAsyncPlatform(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    if (key.scheme == SignatureScheme.Ed25519 && !webCryptoEd25519Available()) {
        throw UnsupportedOperationException("Ed25519 is not available on this WebCrypto engine")
    }
    return webCryptoVerify(
        key.scheme,
        key.requireInMemoryMaterial().toHexString(),
        message.toHexString(),
        signature.toHexString(),
    )
}

actual suspend fun ed25519AsyncAvailable(): Boolean = webCryptoEd25519Available()

actual val supportsEcdsaSigningFromScalar: Boolean get() = true

internal actual suspend fun generateSigningKeyAsyncPlatform(
    scheme: SignatureScheme,
    factory: BufferFactory,
): SyncCapableSigningKey {
    if (scheme == SignatureScheme.Ed25519 && !webCryptoEd25519Available()) {
        throw UnsupportedOperationException("Ed25519 is not available on this WebCrypto engine")
    }
    val parts = webCryptoGenerateKeyPair(scheme).split(":")
    val verifyKey = verifyKeyOf(scheme, hexToBuffer(parts[1], BufferFactory.Default))
    // Stage the secret private material in a wiped buffer; the import factory copies it into `factory`.
    val privBuf = hexToBuffer(parts[0], secureScratch)
    return try {
        signingKeyOf(scheme, privBuf, verifyKey, factory)
    } finally {
        privBuf.freeNativeMemory()
    }
}
