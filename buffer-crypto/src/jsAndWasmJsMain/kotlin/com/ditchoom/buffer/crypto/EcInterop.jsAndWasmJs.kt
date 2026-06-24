package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer

/*
 * js / wasmJs EC point decompression marshals through hex, the same convention the WebCrypto glue uses
 * (the Kotlin side never holds a typed array). The field arithmetic itself runs in the host engine's
 * built-in BigInt — see the `js(...)` / `@JsFun` literal in each platform actual. WebCrypto can't
 * decompress (`importKey('raw')` requires the uncompressed point), so BigInt is the portable basis;
 * it runs only on the public X coordinate, so its variable-time math leaks nothing.
 */

private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_RADIX = 16

/** Lowercase hex of [len] bytes of [buf] starting at absolute [start]. */
internal fun readFieldHex(
    buf: ReadBuffer,
    start: Int,
    len: Int,
): String {
    val sb = StringBuilder(len * 2)
    for (i in 0 until len) {
        val v = buf.get(start + i).toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0xF])
    }
    return sb.toString()
}

/** Writes a hex string's bytes into a fresh read-ready buffer from [factory]. */
internal fun hexToReadBuffer(
    hex: String,
    factory: BufferFactory,
): ReadBuffer {
    val n = hex.length / 2
    val out = factory.allocate(n)
    for (i in 0 until n) out.writeByte(hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte())
    out.resetForRead()
    return out
}
