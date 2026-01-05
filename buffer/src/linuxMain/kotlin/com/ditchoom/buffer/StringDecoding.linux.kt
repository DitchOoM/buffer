package com.ditchoom.buffer

/**
 * Linux/Native implementation of string decoding.
 * Only UTF-8 is supported on Linux platforms.
 */
internal actual fun decodeByteArrayToString(
    data: ByteArray,
    startIndex: Int,
    endIndex: Int,
    charset: Charset,
): String =
    when (charset) {
        Charset.UTF8 ->
            data.decodeToString(
                startIndex,
                endIndex,
                throwOnInvalidSequence = true,
            )
        else ->
            throw UnsupportedOperationException(
                "Linux platforms only support UTF-8 charset. Got: $charset",
            )
    }
