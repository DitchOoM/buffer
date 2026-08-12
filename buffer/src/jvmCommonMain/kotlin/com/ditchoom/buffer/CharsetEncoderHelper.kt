package com.ditchoom.buffer

import java.nio.charset.CharsetEncoder

fun Charset.toEncoder(): CharsetEncoder =
    when (this) {
        Charset.UTF8 -> utf8Encoder
        Charset.UTF16 -> utf16Encoder
        Charset.UTF16BigEndian -> utf16BEEncoder
        Charset.UTF16LittleEndian -> utf16LEEncoder
        Charset.ASCII -> utfAsciiEncoder
        Charset.ISOLatin1 -> utfISOLatin1Encoder
        Charset.UTF32 -> utf32Encoder
        Charset.UTF32LittleEndian -> utf32LEEncoder
        Charset.UTF32BigEndian -> utf32BEEncoder
    }.get()

internal class DefaultEncoder(
    private val charset: java.nio.charset.Charset,
) : ThreadLocal<CharsetEncoder>() {
    override fun initialValue(): CharsetEncoder? = charset.newEncoder()

    override fun get(): CharsetEncoder = super.get()!!
}

internal val utf8Encoder = DefaultEncoder(Charsets.UTF_8)

/** U+FFFD REPLACEMENT CHARACTER encoded as UTF-8. */
private val UTF8_REPLACEMENT_BYTES = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

/**
 * Substituting UTF-8 encoder for the [Utf8.Lenient] heap fallback: unpaired surrogates become
 * U+FFFD (the JDK's default UTF-8 replacement is `?`, which would break the cross-platform
 * byte contract pinned by `TextEncodingTests`).
 */
internal val utf8LenientEncoder =
    object : ThreadLocal<CharsetEncoder>() {
        override fun initialValue(): CharsetEncoder =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .replaceWith(UTF8_REPLACEMENT_BYTES)

        override fun get(): CharsetEncoder = super.get()!!
    }
internal val utf16Encoder = DefaultEncoder(Charsets.UTF_16)
internal val utf16BEEncoder = DefaultEncoder(Charsets.UTF_16BE)
internal val utf16LEEncoder = DefaultEncoder(Charsets.UTF_16LE)
internal val utfAsciiEncoder = DefaultEncoder(Charsets.US_ASCII)
internal val utfISOLatin1Encoder = DefaultEncoder(Charsets.ISO_8859_1)
internal val utf32Encoder = DefaultEncoder(Charsets.UTF_32)
internal val utf32LEEncoder = DefaultEncoder(Charsets.UTF_32LE)
internal val utf32BEEncoder = DefaultEncoder(Charsets.UTF_32BE)
