package com.ditchoom.buffer

/**
 * Decode a portion of a ByteArray to a String using the specified charset.
 *
 * Platform implementations:
 * - WASM: Uses TextDecoder API for full charset support
 * - Native: Uses Kotlin's decodeToString (UTF-8 only, throws for other charsets)
 */
internal expect fun decodeByteArrayToString(
    data: ByteArray,
    startIndex: Int,
    endIndex: Int,
    charset: Charset,
): String
