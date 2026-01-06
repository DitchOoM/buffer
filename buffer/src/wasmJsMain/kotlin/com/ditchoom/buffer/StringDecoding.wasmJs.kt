@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Decode bytes to a string using the specified encoding.
 * Creates a Uint8Array from the byte values and uses TextDecoder.
 *
 * Note: In Kotlin/WASM, ByteArray lives in the WasmGC heap (separate from linear memory),
 * so we cannot directly cast to Uint8Array like in Kotlin/JS. A copy is required.
 * This implementation batches the copy in JS for better performance.
 */
@JsFun(
    """
(size, getByte, encoding) => {
    const bytes = new Uint8Array(size);
    for (let i = 0; i < size; i++) {
        bytes[i] = getByte(i);
    }
    const decoder = new TextDecoder(encoding, { fatal: true });
    return decoder.decode(bytes);
}
""",
)
private external fun decodeWithCallback(
    size: Int,
    getByte: (Int) -> Byte,
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
    return decodeWithCallback(length, { i -> data[startIndex + i] }, encoding.toJsString()).toString()
}
