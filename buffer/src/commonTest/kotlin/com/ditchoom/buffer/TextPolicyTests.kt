// Surrogate code units (0xD800..0xDFFF), UTF-8 byte values, and sizing ratios are the
// subject under test; naming each literal would obscure the cases rather than clarify them.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the [TextPolicy] contract on every platform:
 *
 * 1. `size(text)` == bytes written by `writeText(text, policy)` — exactly, for any input;
 * 2. identical bytes for identical `(text, encoding)` on every platform (explicit vectors);
 * 3. strict rejection is atomic — position unchanged;
 * 4. wrappers stay transparent and fluent results identify the wrapper, not the inner buffer.
 */
class TextPolicyTests {
    // Unpaired-surrogate STRINGS must be constructed at runtime: the Kotlin/JS compiler's
// clean-build codegen lossily rewrites unpaired surrogates in string LITERALS to '?'
// (incremental builds emit them faithfully — the divergence was caught when a clean
// rebuild flipped these tests). Char() is numeric and safe. Valid pairs are unaffected.
    private val loneHigh = Char(0xD800).toString()
    private val loneHighEnd = Char(0xDBFF).toString()
    private val loneLow = Char(0xDC00).toString()
    private val loneLowEnd = Char(0xDFFF).toString()
    private val wellFormed =
        listOf(
            "",
            "A",
            "yolo swag lyfestyle",
            "é",
            "€",
            "aé€😀",
            "😀😀😀",
            "\uD800\uDC00", // minimum surrogate pair (U+10000)
            "\uDBFF\uDFFF", // maximum surrogate pair (U+10FFFF)
        )

    private val illFormed =
        listOf(
            loneHigh to 0,
            loneLow to 0,
            (loneHigh + "€") to 0,
            (loneHigh + "A") to 0,
            ("A" + loneHigh) to 1,
            ("AB" + loneHigh) to 2,
            (loneLow + loneHigh) to 0,
            ("😀" + loneHigh) to 2,
        )

    private val factories =
        listOf(
            "default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "deterministic" to BufferFactory.deterministic(),
        )

    // U+FFFD as UTF-8
    private val fffd = listOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

    private fun writtenBytes(
        factory: BufferFactory,
        text: String,
    ): List<Byte> {
        val buffer = factory.allocate(text.length * 3 + 8)
        buffer.writeText(text, Utf8.Lenient)
        val count = buffer.position()
        buffer.resetForRead()
        return buffer.readByteArray(count).toList()
    }

    @Test
    fun lenientSizeMatchesBytesWrittenExactly() {
        for ((name, factory) in factories) {
            for (text in wellFormed + illFormed.map { it.first }) {
                val buffer = factory.allocate(text.length * 3 + 8)
                val returned = buffer.writeText(text, Utf8.Lenient)
                assertSame(buffer, returned, "lenient write must be fluent on the same instance")
                assertEquals(
                    Utf8.Lenient.size(text),
                    buffer.position(),
                    "size(text) must equal bytes written [$name] for ${text.length} chars",
                )
            }
        }
    }

    @Test
    fun lenientSubstitutionBytesArePinned() {
        for ((name, factory) in factories) {
            assertEquals(fffd, writtenBytes(factory, loneHigh), "lone high [$name]")
            assertEquals(fffd, writtenBytes(factory, loneLow), "lone low [$name]")
            assertEquals(
                fffd + listOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()),
                writtenBytes(factory, (loneHigh + "€")),
                "U+FFFD then € [$name]",
            )
            assertEquals(listOf(0x41.toByte()) + fffd, writtenBytes(factory, ("A" + loneHigh)), "A then U+FFFD [$name]")
            assertEquals(
                listOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()),
                writtenBytes(factory, "😀"),
                "valid pair passes through [$name]",
            )
        }
    }

    @Test
    fun checkedAcceptsWellFormedWithSameBytesAsLenient() {
        for ((name, factory) in factories) {
            for (text in wellFormed) {
                val buffer = factory.allocate(text.length * 3 + 8)
                val outcome = buffer.writeText(text, Utf8.Checked)
                assertIs<TextOutcome.Bytes>(outcome, "well-formed must be accepted [$name]")
                assertEquals(outcome.count, buffer.position(), "reported count must equal bytes written [$name]")
                assertEquals(Utf8.Lenient.size(text), outcome.count, "strict and lenient agree on well-formed [$name]")
            }
        }
    }

    @Test
    fun checkedRejectsIllFormedAtomically() {
        for ((name, factory) in factories) {
            for ((text, expectedIndex) in illFormed) {
                val buffer = factory.allocate(64)
                buffer.writeInt(42) // non-zero starting position
                val before = buffer.position()
                val outcome = buffer.writeText(text, Utf8.Checked)
                assertIs<TextOutcome.Malformed>(outcome, "ill-formed must be rejected [$name]")
                assertEquals(expectedIndex, outcome.index, "index of first unpaired surrogate [$name]")
                assertEquals(before, buffer.position(), "rejection must not move position [$name]")
            }
        }
    }

    @Test
    fun checkedSizeAgreesWithCheckedWrite() {
        for (text in wellFormed) {
            val sized = Utf8.Checked.size(text)
            assertIs<TextOutcome.Bytes>(sized)
            assertEquals(Utf8.Lenient.size(text), sized.count)
        }
        for ((text, expectedIndex) in illFormed) {
            val sized = Utf8.Checked.size(text)
            assertIs<TextOutcome.Malformed>(sized)
            assertEquals(expectedIndex, sized.index)
        }
    }

    @Test
    fun oneArgWriteTextIsLenient() {
        val buffer = BufferFactory.Default.allocate(16)
        assertSame(buffer, buffer.writeText(loneHigh))
        assertEquals(3, buffer.position())
    }

    @Test
    fun utf8SizeMatchesDeprecatedUtf8Length() {
        for (text in wellFormed + illFormed.map { it.first }) {
            @Suppress("DEPRECATION")
            assertEquals(text.utf8Length(), text.utf8Size(), "utf8Size must preserve utf8Length counts")
        }
    }

    @Test
    fun writeTextThroughPooledBufferAndTrackedSliceStaysFluentAndTransparent() {
        withPool(defaultBufferSize = 256) { pool ->
            pool.withBuffer(128) { pooled ->
                val returned = pooled.writeText(("a" + loneHigh + "€"), Utf8.Lenient)
                assertSame(pooled, returned, "fluent result must be the wrapper, not the inner buffer")
                assertEquals(7, pooled.position(), "1 + 3 (U+FFFD) + 3 (€)")

                val slice = pooled.slice()
                val sliceReturned = slice.writeText("😀", Utf8.Lenient)
                assertSame(slice, sliceReturned, "slice fluent result must be the tracked slice")

                val checked = pooled.writeText(loneLow, Utf8.Checked)
                assertIs<TextOutcome.Malformed>(checked)
                assertEquals(7, pooled.position(), "strict rejection through wrapper must not move position")
            }
        }
    }

    @Test
    fun toReadBufferSizingStrategiesRoundTrip() {
        val text = "aé€😀 plus a tail to exercise more than one word"
        val expectedBytes = Utf8.Lenient.size(text)
        for (sizing in listOf(SizeHint.Exact, SizeHint.UpperBound, SizeHint.BytesPerChar(3f))) {
            val readBuffer = text.toReadBuffer(Utf8.Lenient, sizing)
            assertEquals(expectedBytes, readBuffer.remaining(), "sizing $sizing must expose exactly the text bytes")
            assertEquals(text, readBuffer.readString(expectedBytes, Charset.UTF8), "round-trip [$sizing]")
        }
    }

    @Test
    fun toReadBufferSubstitutesIllFormedText() {
        val readBuffer = (loneHigh + "€").toReadBuffer(Utf8.Lenient, SizeHint.Exact)
        assertEquals(6, readBuffer.remaining())
        assertEquals(fffd + listOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()), readBuffer.readByteArray(6).toList())
    }

    @Test
    fun toReadBufferEmptyTextIsEmptyBuffer() {
        assertEquals(0, "".toReadBuffer(Utf8.Lenient).remaining())
    }

    @Test
    fun bytesPerCharRatioIsValidated() {
        assertFailsWith<IllegalArgumentException> { SizeHint.BytesPerChar(0.5f) }
        assertFailsWith<IllegalArgumentException> { SizeHint.BytesPerChar(4.5f) }
        SizeHint.BytesPerChar(1f)
        SizeHint.BytesPerChar(4f)
    }

    @Test
    fun undersizedBytesPerCharGuessOverflowsLoudly() {
        // 1 byte/char cannot hold 3-byte € chars: overflow is a thrown bug-signal, never a short write.
        assertFailsWith<BufferOverflowException> {
            "€€€€€€€€".toReadBuffer(Utf8.Lenient, SizeHint.BytesPerChar(1f))
        }
    }

    @Test
    fun overflowThrowsInsteadOfShortWrite() {
        val buffer = BufferFactory.Default.allocate(2)
        assertFailsWith<BufferOverflowException> { buffer.writeText("€", Utf8.Lenient) }
    }

    @Test
    fun correctedCharsetRatiosRemainTrueUpperBounds() {
        for (text in wellFormed + illFormed.map { it.first }) {
            @Suppress("DEPRECATION")
            val bound = text.maxBufferSize(Charset.UTF8)
            assertTrue(
                Utf8.Lenient.size(text) <= bound,
                "UTF-8 maxBytesPerChar must upper-bound the lenient encoding for $text",
            )
        }
    }
}
