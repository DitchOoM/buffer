package com.ditchoom.buffer.codec

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Built-in [Codec] for 7-bit ASCII text (US-ASCII, RFC 20). One byte per
 * character on the wire, range 0x00..0x7F.
 *
 * Plug into a `String` field via [com.ditchoom.buffer.codec.annotations.UseCodec],
 * typically paired with [com.ditchoom.buffer.codec.annotations.LengthPrefixed]
 * (or [com.ditchoom.buffer.codec.annotations.RemainingBytes]) so the framework
 * narrows `buffer.limit()` to the field's body before calling [decode]:
 *
 * ```kotlin
 * @ProtocolMessage
 * data class StompFrame(
 *     @LengthPrefixed @UseCodec(AsciiStringCodec::class) val command: String,
 * )
 * ```
 *
 * ## Charset semantics
 *
 * - **Encode**: every character in `value` must be in `0x00..0x7F`. The first
 *   character outside that range raises [EncodeException] with the offending
 *   index and codepoint — the codec refuses to silently emit `?` substitutes
 *   or platform-specific lossy fallbacks. After validation, encoding routes
 *   through [WriteBuffer.writeString] with [Charset.UTF8]: ASCII is a strict
 *   subset of UTF-8 (each `0x00..0x7F` codepoint encodes to a single byte
 *   with the same value), so the wire form is identical and the codec
 *   inherits the platform's fast path (e.g. `TextEncoder.encodeInto` on JS).
 * - **Decode**: routed through [ReadBuffer.readString] with [Charset.UTF8].
 *   ASCII-conformant input produces the same codepoints; bytes ≥ 0x80 are
 *   either replaced or rejected per the platform's UTF-8 decoder (JS uses
 *   `{fatal: true}` and throws). Producers conforming to RFC 20 will not
 *   emit those bytes.
 *
 * The body length is owned by the surrounding annotation: `@LengthPrefixed`
 * inserts the prefix in front of the body, `@RemainingBytes` consumes to
 * `buffer.limit()`. [decode] reads `buffer.remaining()` bytes — i.e. the
 * already-bounded region. Standalone callers must bound the buffer themselves
 * (e.g. via `setLimit`) before invoking [decode].
 *
 * ## Adding other built-in charsets
 *
 * This codec is the only built-in string codec. To support Latin-1, UTF-16,
 * Modified UTF-8, etc., copy this class and swap the ASCII validation /
 * wire-size formula for the target charset's encoding rules. See
 * [com.ditchoom.buffer.codec.annotations.UseCodec] for the extension pattern.
 */
object AsciiStringCodec : Codec<String> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): String = buffer.readString(buffer.remaining(), Charset.UTF8)

    override fun encode(
        buffer: WriteBuffer,
        value: String,
        context: EncodeContext,
    ) {
        for (i in value.indices) {
            val code = value[i].code
            if (code > 0x7F) {
                throw EncodeException(
                    fieldPath = "AsciiStringCodec",
                    reason =
                        "non-ASCII character at index $i: U+" +
                            code.toString(16).uppercase().padStart(4, '0') +
                            " (allowed range: U+0000..U+007F)",
                )
            }
        }
        buffer.writeString(value, Charset.UTF8)
    }

    override fun wireSize(
        value: String,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.length)

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
