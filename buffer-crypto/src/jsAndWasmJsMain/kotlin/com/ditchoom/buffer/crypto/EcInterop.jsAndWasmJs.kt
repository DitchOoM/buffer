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
