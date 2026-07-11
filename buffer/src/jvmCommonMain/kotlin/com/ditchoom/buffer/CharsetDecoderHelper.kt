package com.ditchoom.buffer

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

internal fun Charset.toDecoder(): CharsetDecoder =
    when (this) {
        Charset.UTF8 -> utf8Decoder
        Charset.UTF16 -> utf16Decoder
        Charset.UTF16BigEndian -> utf16BEDecoder
        Charset.UTF16LittleEndian -> utf16LEDecoder
        Charset.ASCII -> utfAsciiDecoder
        Charset.ISOLatin1 -> utfISOLatin1Decoder
        Charset.UTF32 -> utf32Decoder
        Charset.UTF32LittleEndian -> utf32LEDecoder
        Charset.UTF32BigEndian -> utf32BEDecoder
    }.get()

/**
 * Per-thread [CharsetDecoder]. Mirrors [DefaultEncoder]: constructing a decoder per
 * [ReadBuffer.readString] call allocated a fresh decoder plus a `HeapCharBuffer` sized to the
 * whole payload — the dominant JVM allocation on the WebSocket receive path. A thread-local
 * decoder is safe because `readString` is non-suspend and decodes synchronously start to finish,
 * so the decoder is never shared across a suspension point. The REPORT actions are configured once
 * here (they survive [CharsetDecoder.reset]) instead of on every call.
 */
internal class DefaultDecoder(
    private val charset: java.nio.charset.Charset,
) : ThreadLocal<CharsetDecoder>() {
    override fun initialValue(): CharsetDecoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    override fun get(): CharsetDecoder = super.get()!!
}

internal val utf8Decoder = DefaultDecoder(Charsets.UTF_8)
internal val utf16Decoder = DefaultDecoder(Charsets.UTF_16)
internal val utf16BEDecoder = DefaultDecoder(Charsets.UTF_16BE)
internal val utf16LEDecoder = DefaultDecoder(Charsets.UTF_16LE)
internal val utfAsciiDecoder = DefaultDecoder(Charsets.US_ASCII)
internal val utfISOLatin1Decoder = DefaultDecoder(Charsets.ISO_8859_1)
internal val utf32Decoder = DefaultDecoder(Charsets.UTF_32)
internal val utf32LEDecoder = DefaultDecoder(Charsets.UTF_32LE)
internal val utf32BEDecoder = DefaultDecoder(Charsets.UTF_32BE)

private const val INITIAL_DECODE_BUFFER_CHARS = 1024

/**
 * Per-thread scratch [CharBuffer] that [decodeReusing] decodes into, grown on demand and kept for
 * subsequent calls so steady-state reads of similar-sized payloads allocate nothing but the result
 * String. Shared across charsets on the thread; each call clears it before use.
 */
private val decodeCharBufferHolder =
    object : ThreadLocal<CharBuffer>() {
        override fun initialValue(): CharBuffer = CharBuffer.allocate(INITIAL_DECODE_BUFFER_CHARS)
    }

/**
 * Decode [byteCount] bytes of [input] into a String using this thread-local decoder and a reused
 * thread-local [CharBuffer], avoiding the per-call decoder + `HeapCharBuffer` allocation that
 * [CharsetDecoder.decode] performs. [input] must have exactly [byteCount] bytes remaining.
 *
 * The scratch buffer is sized at `maxCharsPerByte * byteCount`, the decoder's guaranteed upper
 * bound on produced chars, so a single `decode(endOfInput=true)` + `flush` never overflows.
 */
internal fun CharsetDecoder.decodeReusing(
    input: ByteBuffer,
    byteCount: Int,
): String {
    reset()
    val requiredChars = (byteCount * maxCharsPerByte()).toInt() + 1
    var out = decodeCharBufferHolder.get()
    if (out.capacity() < requiredChars) {
        out = CharBuffer.allocate(requiredChars)
        decodeCharBufferHolder.set(out)
    } else {
        (out as Buffer).clear()
    }

    val decodeResult = decode(input, out, true)
    if (decodeResult.isError) {
        decodeResult.throwException()
    }
    check(!decodeResult.isOverflow) {
        "Decode overflow despite maxCharsPerByte sizing (byteCount=$byteCount, capacity=${out.capacity()})"
    }

    val flushResult = flush(out)
    if (flushResult.isError) {
        flushResult.throwException()
    }
    check(!flushResult.isOverflow) {
        "Flush overflow despite maxCharsPerByte sizing (byteCount=$byteCount, capacity=${out.capacity()})"
    }

    (out as Buffer).flip()
    return out.toString()
}
