// UTF-8 byte patterns and code points are the subject under test; naming each literal
// would obscure the cases rather than clarify them.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the [ReadBuffer.readText] contract on every platform and every factory — the decode
 * probe measured SEVEN distinct failure identities for `readString` across the
 * platform × factory matrix (incl. an NPE and two unbranchable JS throwables), so factory
 * identity is a first-class test dimension here, not redundancy.
 */
class ReadTextTests {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private val factories =
        listOf(
            "default" to BufferFactory.Default,
            "managed" to BufferFactory.managed(),
            "deterministic" to BufferFactory.deterministic(),
        )

    private val validAEuro = bytes(0x41, 0xE2, 0x82, 0xAC)
    private val truncated3Mid = bytes(0x41, 0xE2, 0x82, 0x42)
    private val encodedSurrogate = bytes(0xED, 0xA0, 0x80)

    private fun <T> withData(
        factory: BufferFactory,
        data: ByteArray,
        block: (PlatformBuffer) -> T,
    ): T =
        factory.allocate(data.size + 8).use { buffer ->
            buffer.writeBytes(data)
            buffer.resetForRead()
            block(buffer)
        }

    @Test
    fun lenientDecodesWellFormedText() {
        for ((name, factory) in factories) {
            withData(factory, validAEuro) { buffer ->
                assertEquals("A€", buffer.readText(4, Utf8.Lenient), "well-formed [$name]")
                assertEquals(4, buffer.position(), "position advances by length [$name]")
            }
        }
    }

    @Test
    fun lenientSubstitutesPerMaximalSubpart() {
        for ((name, factory) in factories) {
            withData(factory, truncated3Mid) { buffer ->
                assertEquals("A�B", buffer.readText(4, Utf8.Lenient), "maximal subpart [$name]")
                assertEquals(4, buffer.position(), "lenient always consumes [$name]")
            }
            withData(factory, encodedSurrogate) { buffer ->
                assertEquals("���", buffer.readText(3, Utf8.Lenient), "encoded surrogate [$name]")
            }
        }
    }

    @Test
    fun strictReturnsStringOrThrowsTyped() {
        for ((name, factory) in factories) {
            withData(factory, validAEuro) { buffer ->
                assertEquals("A€", buffer.readText(4, Utf8.Strict), "well-formed [$name]")
            }
            withData(factory, truncated3Mid) { buffer ->
                val e =
                    assertFailsWith<MalformedTextException.IllFormedBytes>("[$name]") {
                        buffer.readText(4, Utf8.Strict)
                    }
                assertEquals(1, e.byteOffset, "subpart start [$name]")
                assertEquals(0, buffer.position(), "rejection is atomic — nothing consumed [$name]")
            }
        }
    }

    @Test
    fun checkedReturnsSealedOutcome() {
        for ((name, factory) in factories) {
            withData(factory, validAEuro) { buffer ->
                val r = buffer.readText(4, Utf8.Checked)
                assertIs<DecodedText.Text>(r, "[$name]")
                assertEquals("A€", r.value)
                assertEquals(4, buffer.position())
            }
            withData(factory, truncated3Mid) { buffer ->
                val r = buffer.readText(4, Utf8.Checked)
                assertIs<DecodedText.Malformed>(r, "[$name]")
                assertEquals(1, r.byteOffset)
                assertEquals(0, buffer.position(), "rejection is atomic [$name]")
            }
        }
    }

    @Test
    fun readTextAfterNonZeroPositionUsesTheRightWindow() {
        for ((name, factory) in factories) {
            withData(factory, bytes(0x01, 0x02, 0x41, 0xE2, 0x82, 0xAC)) { buffer ->
                buffer.readShort() // consume prefix
                assertEquals("A€", buffer.readText(4, Utf8.Strict), "windowed [$name]")
                assertEquals(6, buffer.position())
            }
        }
    }

    @Test
    fun strictRejectionAtNonZeroPositionRewindsToStart() {
        for ((name, factory) in factories) {
            withData(factory, bytes(0x01, 0x80, 0x80)) { buffer ->
                buffer.readByte()
                assertFailsWith<MalformedTextException.IllFormedBytes>("[$name]") {
                    buffer.readText(2, Utf8.Strict)
                }
                assertEquals(1, buffer.position(), "rewinds to pre-read position, not zero [$name]")
            }
        }
    }

    @Test
    fun oneArgReadTextIsLenient() {
        withData(BufferFactory.Default, encodedSurrogate) { buffer ->
            assertEquals("���", buffer.readText(3))
        }
    }

    @Test
    fun writeReadRoundTripsUnderOnePolicy() {
        // The unified policy object: what one direction writes, the other reads — per policy.
        val text = "aé€😀 and a tail"
        for ((name, factory) in factories) {
            factory.allocate(64).use { buffer ->
                buffer.writeText(text, Utf8.Strict)
                buffer.resetForRead()
                assertEquals(text, buffer.readText(text.utf8Size(), Utf8.Strict), "round-trip [$name]")
            }
        }
    }

    @Test
    fun readTextThroughPooledBufferAndTrackedSlice() {
        withPool(defaultBufferSize = 128) { pool ->
            pool.withBuffer(64) { pooled ->
                pooled.writeBytes(truncated3Mid)
                pooled.resetForRead()
                val r = pooled.readText(4, Utf8.Checked)
                assertIs<DecodedText.Malformed>(r)
                assertEquals(0, pooled.position(), "atomic through the wrapper")
                assertEquals("A�B", pooled.readText(4, Utf8.Lenient), "lenient through the wrapper")
            }
        }
    }

    @Test
    fun customPolicyComposesHalves() {
        // Custom encoder (uppercases ASCII then delegates bytes) + library decoder.
        val shoutingEncoder =
            object : TextEncoder {
                override fun encodeInto(
                    buffer: WriteBuffer,
                    text: CharSequence,
                ): Int {
                    val upper = text.toString().uppercase()
                    val bytes = Utf8TextEncoder.encodeSubstituting(upper)
                    buffer.writeBytes(bytes)
                    return bytes.size
                }
            }
        val policy = TextPolicy.custom(shoutingEncoder, Utf8.Lenient.decoder)

        BufferFactory.Default.allocate(32).use { buffer ->
            val returned = buffer.writeText("hello", policy)
            assertSame(buffer, returned, "custom policies are fluent")
            assertEquals(5, buffer.position())
            buffer.resetForRead()
            assertEquals("HELLO", buffer.readText(5, policy), "decode via the composed library half")
        }

        // Derived size: computed by running the author's own encode against a counting sink.
        assertEquals(5, policy.size("hello"))
        assertEquals("é€".uppercase().let { Utf8TextEncoder.sizeSubstituting(it) }, policy.size("é€"))
    }
}

/** The kit itself must catch violations — verified with a deliberately lying transcoder. */
class TextTranscoderContractKitTests {
    private val honest =
        object : TextEncoder {
            override fun encodeInto(
                buffer: WriteBuffer,
                text: CharSequence,
            ): Int {
                val bytes = Utf8TextEncoder.encodeSubstituting(text.toString())
                buffer.writeBytes(bytes)
                return bytes.size
            }
        }

    @Test
    fun honestTranscoderPasses() {
        TextTranscoderContractKit.verify(
            TextPolicy.custom(honest, Utf8.Lenient.decoder),
            listOf("", "hello", "aé€😀"),
        )
    }

    @Test
    fun lyingSizeOverrideIsCaught() {
        val liar =
            object : TextEncoder by honest {
                override fun size(text: CharSequence): Int = text.length // under-counts multi-byte
            }
        assertFailsWith<AssertionError> {
            TextTranscoderContractKit.verify(TextPolicy.custom(liar, Utf8.Lenient.decoder), listOf("é€"))
        }
    }

    @Test
    fun shortConsumingDecoderIsCaught() {
        val shortReader =
            object : TextDecoder {
                override fun decodeFrom(
                    buffer: ReadBuffer,
                    length: Int,
                ): String = buffer.readText(length - 1, Utf8.Lenient) // consumes one byte short
            }
        assertFailsWith<AssertionError> {
            TextTranscoderContractKit.verify(TextPolicy.custom(honest, shortReader), listOf("ab"))
        }
    }
}
