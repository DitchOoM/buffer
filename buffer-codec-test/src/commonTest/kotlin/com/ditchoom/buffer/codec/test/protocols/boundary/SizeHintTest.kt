package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.count.CountNamed
import com.ditchoom.buffer.codec.test.protocols.count.CountNamedCodec
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableList
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableListCodec
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStrings
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStringsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `sizeHint` — the O(field count) starting-capacity guess consulted by
 * `encodeToPlatformBuffer` for BackPatch shapes. Contract under test:
 *
 * 1. For ASCII content the hint equals the encoded byte count exactly
 *    (1 char = 1 UTF-8 byte), so the first allocation fits — zero retries.
 * 2. For any content the hint is a LOWER bound on the encoded size (every
 *    Char encodes to ≥ 1 byte) — an under-hint only costs doublings, never
 *    correctness.
 * 3. Hints compose through nested sealed fields and list elements.
 * 4. Encoders without an override keep the documented default.
 */
class SizeHintTest {
    private fun <T> encodedSize(
        codec: Encoder<T>,
        value: T,
    ): Int = codec.encodeToPlatformBuffer(value).limit()

    @Test
    fun asciiHintMatchesEncodedSizeExactly() {
        val msg = TwoStrings("hello", "world!")
        assertEquals(4 + 5 + 6, TwoStringsCodec.sizeHint(msg, EncodeContext.Empty))
        assertEquals(encodedSize(TwoStringsCodec, msg), TwoStringsCodec.sizeHint(msg, EncodeContext.Empty))
    }

    @Test
    fun hintIsLowerBoundForMultibyteContent() {
        for (msg in listOf(
            TwoStrings("héllo", "wörld"),
            TwoStrings("日本語のテキスト", "byte"),
            TwoStrings("mixed 语言 content", "🎉🎊"),
        )) {
            val hint = TwoStringsCodec.sizeHint(msg, EncodeContext.Empty)
            val actual = encodedSize(TwoStringsCodec, msg)
            assertTrue(hint <= actual, "hint $hint must not exceed encoded size $actual for $msg")
        }
    }

    @Test
    fun hintComposesThroughNestedSealedField() {
        // 2 (host prefix) + 4 (host) + 1 (discriminator) + 2 (name prefix) + 3 (name).
        val msg = BoundaryHost("host", BoundaryDisp.Named("abc"))
        assertEquals(12, BoundaryHostCodec.sizeHint(msg, EncodeContext.Empty))
        assertEquals(encodedSize(BoundaryHostCodec, msg), BoundaryHostCodec.sizeHint(msg, EncodeContext.Empty))
    }

    @Test
    fun hintComposesThroughCountListElements() {
        // varint(count) min 1 + per element (2-byte prefix + chars).
        val msg = CountVariableList(List(3) { CountNamed("abcd") })
        assertEquals(1 + 3 * (2 + 4), CountVariableListCodec.sizeHint(msg, EncodeContext.Empty))
        assertEquals(2 + 4, CountNamedCodec.sizeHint(CountNamed("abcd"), EncodeContext.Empty))
    }

    @Test
    fun handWrittenEncoderKeepsDocumentedDefault() {
        val bare =
            object : Encoder<Int> {
                override fun encode(
                    buffer: com.ditchoom.buffer.WriteBuffer,
                    value: Int,
                    context: EncodeContext,
                ) {
                    buffer.writeInt(value)
                }
            }
        assertEquals(Encoder.DEFAULT_SIZE_HINT, bare.sizeHint(7, EncodeContext.Empty))
    }

    @Test
    fun largeAsciiMessageRoundTripsThroughSingleShotAllocation() {
        // 8KB ASCII: hint == exact size, so the first allocation fits.
        val msg = TwoStrings("x".repeat(4000), "y".repeat(4000))
        val buf = TwoStringsCodec.encodeToPlatformBuffer(msg)
        assertEquals(TwoStringsCodec.sizeHint(msg, EncodeContext.Empty), buf.limit())
        assertEquals(msg, TwoStringsCodec.decode(buf, com.ditchoom.buffer.codec.DecodeContext.Empty))
    }
}
