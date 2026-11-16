package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.fromHexString
import com.ditchoom.buffer.toHexString

/*
 * js / wasmJs EC point decompression marshals through hex, the same convention the WebCrypto glue uses
 * (the Kotlin side never holds a typed array). The field arithmetic itself runs in the host engine's
 * built-in BigInt — see the `js(...)` / `@JsFun` literal in each platform actual. WebCrypto can't
 * decompress (`importKey('raw')` requires the uncompressed point), so BigInt is the portable basis;
 * it runs only on the public X coordinate, so its variable-time math leaks nothing.
 */

/** Lowercase hex of [len] bytes of [buf] starting at absolute [start]. */
internal fun readFieldHex(
    buf: ReadBuffer,
    start: Int,
    len: Int,
): String = buf.toHexString(start, len)

/** Writes a hex string's bytes into a fresh read-ready buffer from [factory]. */
internal fun hexToReadBuffer(
    hex: String,
    factory: BufferFactory,
): ReadBuffer = factory.fromHexString(hex)

/*
 * Public point from a private scalar: WebCrypto has no "d·G" call, but it computes the public point
 * when importing a PKCS#8 key that omits it, and an exported private JWK carries `x` and `y`. So the
 * scalar is imported (extractable, as the JWK export requires) and only `x`/`y` are read back. The
 * scalar crosses the boundary as a hex String, with the zeroization limitation documented for the
 * key-agreement bridge.
 */
internal actual suspend fun ecPublicPointFromValidScalar(
    curve: KeyAgreementCurve,
    scalar: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val pkcs8Hex = scalarToPkcs8Hex(curve, scalar)
    return hexToReadBuffer(webCryptoPublicPointFromPkcs8(curve.curveName, pkcs8Hex), factory)
}

/**
 * Per-target WebCrypto glue: imports [pkcs8Hex] as an ECDH private key on [curveName], exports it as a
 * JWK, and returns `04 ‖ x ‖ y` as lowercase hex.
 */
internal expect suspend fun webCryptoPublicPointFromPkcs8(
    curveName: String,
    pkcs8Hex: String,
): String
