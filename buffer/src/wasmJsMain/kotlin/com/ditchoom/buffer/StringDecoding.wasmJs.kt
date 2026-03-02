@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Decode bytes to a string using the specified encoding.
 * Uses getInt callback to fill Uint8Array 4 bytes at a time — 4x fewer WASM→JS crossings.
 *
 * Note: In Kotlin/WASM, ByteArray lives in the WasmGC heap (separate from linear memory),
 * so we cannot directly cast to Uint8Array like in Kotlin/JS. A copy is required.
 */
@JsFun(
    """
(size, getInt, encoding) => {
    const bytes = new Uint8Array(size);
    const fullInts = size >>> 2;
    for (let i = 0; i < fullInts; i++) {
        const v = getInt(i);
        const off = i << 2;
        bytes[off] = v & 0xFF;
        bytes[off + 1] = (v >>> 8) & 0xFF;
        bytes[off + 2] = (v >>> 16) & 0xFF;
        bytes[off + 3] = (v >>> 24) & 0xFF;
    }
    const tailStart = fullInts << 2;
    if (tailStart < size) {
        const v = getInt(fullInts);
        for (let j = 0; j < size - tailStart; j++) {
            bytes[tailStart + j] = (v >>> (j << 3)) & 0xFF;
        }
    }
    const decoder = new TextDecoder(encoding, { fatal: true });
    return decoder.decode(bytes);
}
""",
)
private external fun decodeWithIntCallback(
    size: Int,
    getInt: (Int) -> Int,
    encoding: JsString,
): JsString

internal actual fun decodeByteArrayToString(
    data: ByteArray,
    startIndex: Int,
    endIndex: Int,
    charset: Charset,
): String {
    // For UTF-8, use Kotlin's built-in decoder (faster, no JS interop needed)
    if (charset == Charset.UTF8) {
        return data.decodeToString(startIndex, endIndex, throwOnInvalidSequence = true)
    }

    val encoding =
        when (charset) {
            Charset.UTF8 -> "utf-8"
            Charset.UTF16 -> "utf-16"
            Charset.UTF16BigEndian -> "utf-16be"
            Charset.UTF16LittleEndian -> "utf-16le"
            Charset.ASCII -> "ascii"
            Charset.ISOLatin1 -> "iso-8859-1"
            Charset.UTF32,
            Charset.UTF32LittleEndian,
            Charset.UTF32BigEndian,
            -> throw UnsupportedOperationException("UTF-32 charsets are not supported by TextDecoder")
        }

    val length = endIndex - startIndex
    return decodeWithIntCallback(
        length,
        { intIndex ->
            val byteIndex = startIndex + (intIndex shl 2)
            val remainingBytes = length - (intIndex shl 2)
            if (remainingBytes >= 4) {
                (data[byteIndex].toInt() and 0xFF) or
                    ((data[byteIndex + 1].toInt() and 0xFF) shl 8) or
                    ((data[byteIndex + 2].toInt() and 0xFF) shl 16) or
                    ((data[byteIndex + 3].toInt() and 0xFF) shl 24)
            } else {
                var v = 0
                for (j in 0 until remainingBytes) {
                    v = v or ((data[byteIndex + j].toInt() and 0xFF) shl (j shl 3))
                }
                v
            }
        },
        encoding.toJsString(),
    ).toString()
}
