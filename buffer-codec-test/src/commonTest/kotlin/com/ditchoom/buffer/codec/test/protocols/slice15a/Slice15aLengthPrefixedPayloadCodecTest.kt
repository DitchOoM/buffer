package com.ditchoom.buffer.codec.test.protocols.slice15a

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round-trip + wire-byte + wireSize + peek
 * coverage for the new `@LengthPrefixed @UseCodec(C::class) val: T :
 * Payload` shape. Pure capability slice; v5 substitution lands in
 * slices 15c/15d.
 *
 * Wire format: 2-byte UShort BE prefix + body bytes consumed by
 * [BinaryDataCodec]. The framework owns the prefix; the codec is
 * `Codec<BinaryData>` (not a `BoundingLengthCodec`).
 */
class Slice15aLengthPrefixedPayloadCodecTest {
    @Test
    fun encodesShortPayloadByteExact() {
        // 3-byte body 'a','b','c' → 00 03 'a' 'b' 'c'.
        val msg =
            Slice15aLengthPrefixedPayload(
                data = BinaryData(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())),
            )
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(
            byteArrayOf(0x00, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun encodesEmptyPayloadAsZeroLengthPrefix() {
        // Empty body → 00 00 (UShort BE prefix = 0, no body bytes).
        val msg = Slice15aLengthPrefixedPayload(data = BinaryData(ByteArray(0)))
        val buf = encode(msg)
        buf.resetForRead()
        assertContentEquals(byteArrayOf(0x00, 0x00), buf.readByteArray(buf.remaining()))
    }

    @Test
    fun roundTripsShortPayload() {
        val original =
            Slice15aLengthPrefixedPayload(
                data = BinaryData(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)),
            )
        val buf = encode(original)
        buf.resetForRead()
        val decoded = Slice15aLengthPrefixedPayloadCodec.decode(buf, DecodeContext.Empty)
        assertContentEquals(original.data.bytes, decoded.data.bytes)
    }

    @Test
    fun roundTripsTwoBytePrefixBoundaryPayload() {
        // 256-byte body — exercises the second prefix byte (the first byte
        // alone could only encode 0..255). UShort BE prefix = 0x0100.
        val bytes = ByteArray(256) { (it and 0xFF).toByte() }
        val original = Slice15aLengthPrefixedPayload(data = BinaryData(bytes))
        val buf = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
        Slice15aLengthPrefixedPayloadCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val all = buf.readByteArray(buf.remaining())
        assertEquals(0x01.toByte(), all[0])
        assertEquals(0x00.toByte(), all[1])
        assertEquals(258, all.size)
        // Round-trip the decoded body.
        val decodeBuf = BufferFactory.Default.allocate(all.size, ByteOrder.BIG_ENDIAN)
        decodeBuf.writeBytes(all)
        decodeBuf.resetForRead()
        val decoded = Slice15aLengthPrefixedPayloadCodec.decode(decodeBuf, DecodeContext.Empty)
        assertContentEquals(bytes, decoded.data.bytes)
    }

    @Test
    fun decodeBoundsCodecToPrefixLengthEvenIfBufferHasMoreBytes() {
        // The framework must narrow `buffer.limit()` to position+length
        // before delegating to BinaryDataCodec.decode — otherwise the
        // codec's `readByteArray(buffer.remaining())` would over-read
        // into trailing bytes that don't belong to the field.
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val wire =
            byteArrayOf(0x00, 0x03) + payload +
                byteArrayOf(0xFE.toByte(), 0xED.toByte()) // trailing bytes
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(wire)
        buf.resetForRead()
        val decoded = Slice15aLengthPrefixedPayloadCodec.decode(buf, DecodeContext.Empty)
        assertContentEquals(payload, decoded.data.bytes)
        // Exactly 5 bytes consumed (2 prefix + 3 body); the trailing 2
        // bytes are still readable from the original (outer) limit.
        assertEquals(2, buf.remaining())
    }

    @Test
    fun wireSizeComposesUserCodecExactForTopLevelMessage() {
        // BinaryDataCodec declares Exact(byteSize()); the message-level
        // wireSize probes it and composes 2 (Short prefix) + 1 (body byte).
        // Before the @UseCodec promotion this collapsed to BackPatch.
        val msg = Slice15aLengthPrefixedPayload(data = BinaryData(byteArrayOf(0x42)))
        val ws = Slice15aLengthPrefixedPayloadCodec.wireSize(msg, EncodeContext.Empty)
        assertIs<WireSize.Exact>(ws)
        assertEquals(3, ws.bytes)
    }

    @Test
    fun peekReportsCompleteForFullFrame() {
        // 2-byte prefix + 4-byte body = 6 total bytes. Peek must return
        // Complete(6) without consuming.
        val pool = BufferPool(defaultBufferSize = 32)
        val processor = StreamProcessor.create(pool)
        try {
            val msg = Slice15aLengthPrefixedPayload(data = BinaryData(byteArrayOf(1, 2, 3, 4)))
            val buf = encode(msg)
            buf.resetForRead()
            processor.append(buf)
            val availableBefore = processor.available()
            val peek = Slice15aLengthPrefixedPayloadCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(6, peek.bytes)
            assertEquals(availableBefore, processor.available(), "peek must be non-consuming")
        } finally {
            processor.release()
            pool.clear()
        }
    }

    @Test
    fun peekReportsNeedsMoreDataDripFeeding() {
        // Drip-feed bytes one at a time; peek must report NeedsMoreData
        // until every byte of the encoded frame has arrived.
        val pool = BufferPool(defaultBufferSize = 32)
        val processor = StreamProcessor.create(pool)
        try {
            val msg = Slice15aLengthPrefixedPayload(data = BinaryData(byteArrayOf(0x55, 0x66, 0x77)))
            val full = encode(msg)
            full.resetForRead()
            val totalBytes = full.remaining()
            assertEquals(5, totalBytes) // 2 prefix + 3 body

            for (i in 1 until totalBytes) {
                val chunk = BufferFactory.Default.allocate(1)
                chunk.writeByte(full.readByte())
                chunk.resetForRead()
                processor.append(chunk)
                val peek = Slice15aLengthPrefixedPayloadCodec.peekFrameSize(processor)
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
            val peek = Slice15aLengthPrefixedPayloadCodec.peekFrameSize(processor)
            assertTrue(peek is PeekResult.Complete, "expected Complete, got $peek")
            assertEquals(totalBytes, peek.bytes)
        } finally {
            processor.release()
            pool.clear()
        }
    }

    private fun encode(value: Slice15aLengthPrefixedPayload) =
        BufferFactory.Default
            .allocate(64, ByteOrder.BIG_ENDIAN)
            .also { Slice15aLengthPrefixedPayloadCodec.encode(it, value, EncodeContext.Empty) }
}
