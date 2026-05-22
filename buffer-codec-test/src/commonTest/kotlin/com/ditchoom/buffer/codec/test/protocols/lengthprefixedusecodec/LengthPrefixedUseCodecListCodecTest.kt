package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip + peek tests for `@LengthPrefixed
 * @UseCodec val: List<E>`. Two fixtures:
 *   - [PropertyBagFrame] — terminal property-bag (prefix + elements).
 *   - [TaggedPropertyBag] — prior fixed-size byte before the property
 *     bag (exercises `priorBytes` in the peek walker).
 */
class LengthPrefixedUseCodecListCodecTest {
    @Test
    fun emptyPropertyBagRoundTrips() {
        val original = PropertyBagFrame(properties = emptyList())
        val buffer = BufferFactory.Default.allocate(64)
        PropertyBagFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // var-byte-int(0) = 1 byte; no element bytes.
        assertEquals(1, buffer.remaining())
        val decoded = PropertyBagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun singleElementPropertyBagRoundTrips() {
        val original =
            PropertyBagFrame(
                properties = listOf(PropertyEntry(id = 0x21u, value = 0xDEAD_BEEFu)),
            )
        val buffer = BufferFactory.Default.allocate(64)
        PropertyBagFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // var-byte-int(5) = 1 byte; 1 element * 5 bytes = 5; total = 6.
        assertEquals(6, buffer.remaining())
        val decoded = PropertyBagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun multiElementPropertyBagRoundTrips() {
        val original =
            PropertyBagFrame(
                properties =
                    listOf(
                        PropertyEntry(id = 0x01u, value = 0x0000_0001u),
                        PropertyEntry(id = 0x17u, value = 0xFFFF_FFFFu),
                        PropertyEntry(id = 0x42u, value = 0x1234_5678u),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(64)
        PropertyBagFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // var-byte-int(15) = 1 byte; 3 * 5 = 15 element bytes; total = 16.
        assertEquals(16, buffer.remaining())
        val decoded = PropertyBagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun multiByteVarIntLengthRoundTrips() {
        // 26 elements * 5 bytes = 130 bytes; var-byte-int(130) = 2 bytes
        // (continuation bit set on first byte). Exercises a multi-byte
        // prefix path through the codec.
        val original =
            PropertyBagFrame(
                properties = (0 until 26).map { PropertyEntry(id = it.toUByte(), value = it.toUInt()) },
            )
        val buffer = BufferFactory.Default.allocate(256)
        PropertyBagFrameCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        assertEquals(2 + 130, buffer.remaining())
        val decoded = PropertyBagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun propertyBagRestoresOuterLimitAfterDecode() {
        // The self-contained try/finally must restore the outer limit
        // even though the codec narrows it during the element loop. Bytes
        // appended past the encoded frame must still be readable from the
        // post-decode position.
        val frame = PropertyBagFrame(properties = listOf(PropertyEntry(0x10u, 0x11_22_33_44u)))
        val buffer = BufferFactory.Default.allocate(64)
        PropertyBagFrameCodec.encode(buffer, frame, EncodeContext.Empty)
        buffer.writeByte(0xAA.toByte())
        buffer.writeByte(0xBB.toByte())
        buffer.resetForRead()

        val originalLimit = buffer.limit()
        PropertyBagFrameCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(originalLimit, buffer.limit(), "decode must restore the outer buffer limit")
        assertEquals(0xAA.toByte(), buffer.readByte())
        assertEquals(0xBB.toByte(), buffer.readByte())
    }

    @Test
    fun propertyBagWireSizeIsBackPatch() {
        // Conservative collapse — runtime-Exact composition is a follow-on.
        assertEquals(
            WireSize.BackPatch,
            PropertyBagFrameCodec.wireSize(
                PropertyBagFrame(properties = emptyList()),
                EncodeContext.Empty,
            ),
        )
    }

    @Test
    fun propertyBagPeekFrameSizeReportsCompleteForFullFrame() {
        // var-byte-int(15) + 3 * 5 = 1 + 15 = 16 total bytes.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val frame =
                PropertyBagFrame(
                    properties =
                        listOf(
                            PropertyEntry(0x01u, 0x10u),
                            PropertyEntry(0x02u, 0x20u),
                            PropertyEntry(0x03u, 0x30u),
                        ),
                )
            val buffer = BufferFactory.Default.allocate(32)
            PropertyBagFrameCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            processor.append(buffer)
            val peek = PropertyBagFrameCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(16, peek.bytes)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun propertyBagPeekReportsNeedsMoreDataDripFeeding() {
        // Drip-feed bytes one at a time; peek must report NeedsMoreData
        // until every byte of the encoded frame has arrived.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val frame =
                PropertyBagFrame(
                    properties = listOf(PropertyEntry(0x01u, 0x10u), PropertyEntry(0x02u, 0x20u)),
                )
            val full = BufferFactory.Default.allocate(32)
            PropertyBagFrameCodec.encode(full, frame, EncodeContext.Empty)
            full.resetForRead()
            val totalBytes = full.remaining()
            assertEquals(11, totalBytes) // 1 prefix + 2 * 5 elements

            for (i in 1 until totalBytes) {
                val chunk = BufferFactory.Default.allocate(1)
                chunk.writeByte(full.readByte())
                chunk.resetForRead()
                processor.append(chunk)
                val peek = PropertyBagFrameCodec.peekFrameSize(processor)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    peek,
                    "after $i of $totalBytes bytes, expected NeedsMoreData but got $peek",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(full.readByte())
            last.resetForRead()
            processor.append(last)
            val peek = PropertyBagFrameCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(totalBytes, peek.bytes)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun propertyBagPeekDoesNotConsumeBytes() {
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val frame =
                PropertyBagFrame(properties = listOf(PropertyEntry(0x07u, 0x77_77_77_77u)))
            val buffer = BufferFactory.Default.allocate(16)
            PropertyBagFrameCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            processor.append(buffer)
            val availableBefore = processor.available()
            assertEquals(6, availableBefore)
            repeat(5) {
                val peek = PropertyBagFrameCodec.peekFrameSize(processor)
                assertTrue(peek is PeekResult.Complete)
                assertEquals(6, peek.bytes)
                assertEquals(availableBefore, processor.available(), "peek must be non-consuming")
            }
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun taggedPropertyBagRoundTripsWithPriorByte() {
        val original =
            TaggedPropertyBag(
                tag = 0xC3u,
                properties =
                    listOf(
                        PropertyEntry(id = 0x09u, value = 0x0099_0099u),
                        PropertyEntry(id = 0x0Au, value = 0xAAAA_AAAAu),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(64)
        TaggedPropertyBagCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // 1 (tag) + 1 (var-byte-int 10) + 2 * 5 = 12 total bytes.
        assertEquals(12, buffer.remaining())
        val decoded = TaggedPropertyBagCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun taggedPropertyBagPeekAccountsForPriorByte() {
        // Peek total = priorBytes (1) + observed-codec-width + decoded value.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val frame =
                TaggedPropertyBag(
                    tag = 0x55u,
                    properties = listOf(PropertyEntry(0x12u, 0x3456_789Au)),
                )
            val buffer = BufferFactory.Default.allocate(16)
            TaggedPropertyBagCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            processor.append(buffer)
            val peek = TaggedPropertyBagCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(7, peek.bytes) // 1 + 1 + 5
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun taggedPropertyBagPeekNeedsMoreDataWhenOnlyTagPresent() {
        // Only the prior byte is on the stream — the codec hasn't yet
        // received any bytes for the var-byte-int. peekBuffer falls
        // through to NeedsMoreData via the simpleName whitelist.
        val pool = BufferPool(defaultBufferSize = 64)
        val processor = StreamProcessor.create(pool)
        try {
            val chunk = BufferFactory.Default.allocate(1)
            chunk.writeByte(0xAA.toByte())
            chunk.resetForRead()
            processor.append(chunk)
            val peek = TaggedPropertyBagCodec.peekFrameSize(processor)
            assertEquals(PeekResult.NeedsMoreData, peek)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun stringTaggedPropertyBagRoundTrips() {
        // Element with `@LengthPrefixed val: String`
        // has BackPatch wireSize. Pre-2b the analyzer set elementIsSealed
        // = false (because the element is `data class`, not `sealed`),
        // routing the encode through the pre-measure path; the
        // `as WireSize.Exact` cast on a BackPatch wireSize would
        // ClassCastException. After 2b the analyze-time predicate
        // `detectElementBackPatch` walks the element's primary-constructor
        // params and forces the scratch path on `@LengthPrefixed val:
        // String` (and `@When` / `@RemainingBytes` / `@UseCodec`).
        val original =
            StringTaggedPropertyBag(
                properties =
                    listOf(
                        StringTaggedProperty(tag = "alpha", value = 0x11u),
                        StringTaggedProperty(tag = "", value = 0x22u),
                        StringTaggedProperty(tag = "the quick brown fox", value = 0xFFu),
                    ),
            )
        val buffer = BufferFactory.Default.allocate(128)
        StringTaggedPropertyBagCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        val decoded = StringTaggedPropertyBagCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun stringTaggedPropertyBagWireBytesMatchScratchPath() {
        // Independent computation of the expected wire layout: for each
        // element, [lpStringLen (2 BE) | tagBytes | value (1)]; sum lengths,
        // emit MqttRemainingLengthCodec(sumLen) prefix, then concatenate.
        val element1 = StringTaggedProperty(tag = "k", value = 0x07u)
        val element2 = StringTaggedProperty(tag = "kv", value = 0x08u)
        val original = StringTaggedPropertyBag(properties = listOf(element1, element2))

        val buffer = BufferFactory.Default.allocate(64)
        StringTaggedPropertyBagCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()

        val expected = BufferFactory.Default.allocate(64)
        // element1 = 2 (len) + 1 ("k") + 1 (value) = 4 bytes
        // element2 = 2 (len) + 2 ("kv") + 1 (value) = 5 bytes
        // total body = 9 bytes; prefix = MqttRemainingLengthCodec.encode(9) = 1 byte
        MqttRemainingLengthCodec.encode(expected, 9u, EncodeContext.Empty)
        expected.writeUShort(1u)
        expected.writeString("k")
        expected.writeByte(0x07)
        expected.writeUShort(2u)
        expected.writeString("kv")
        expected.writeByte(0x08)
        expected.resetForRead()

        assertEquals(expected.remaining(), buffer.remaining())
        while (expected.remaining() > 0) {
            assertEquals(expected.readByte(), buffer.readByte())
        }
    }

    @Test
    fun multiElementWireMatchesMqttRemainingLengthCodecPrefix() {
        // Verify byte-exact wire layout: prefix bytes via
        // MqttRemainingLengthCodec.encode, then per-element bytes.
        val frame =
            PropertyBagFrame(
                properties = listOf(PropertyEntry(0x01u, 0x02_03_04_05u)),
            )
        val frameBuf = BufferFactory.Default.allocate(16)
        PropertyBagFrameCodec.encode(frameBuf, frame, EncodeContext.Empty)
        frameBuf.resetForRead()

        val expected = BufferFactory.Default.allocate(16)
        MqttRemainingLengthCodec.encode(expected, 5u, EncodeContext.Empty)
        expected.writeByte(0x01)
        expected.writeByte(0x02)
        expected.writeByte(0x03)
        expected.writeByte(0x04)
        expected.writeByte(0x05)
        expected.resetForRead()

        assertEquals(expected.remaining(), frameBuf.remaining())
        while (expected.remaining() > 0) {
            assertEquals(expected.readByte(), frameBuf.readByte())
        }
    }
}
