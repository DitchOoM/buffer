package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1 type-check vector. Validates that the hand-emitted
 * [RiffChunkHeaderCodec] round-trips byte-exact against a real RIFF
 * chunk header from the WAV spec, that the wireSize is Exact(8), and
 * that peekFrameSize walks NeedsMoreData → Complete(8) correctly.
 *
 * Wire vector — opening 8 bytes of any WAV file:
 *   bytes [R, I, F, F, len[0], len[1], len[2], len[3]]
 *   FourCC = 'RIFF' = 0x52_49_46_46
 *   chunkSize is total file size minus 8 (the 'RIFF' tag and the size
 *   field itself), little-endian.
 */
class RiffChunkHeaderCodecTest {
    private val riffTag: UInt = 0x52u shl 24 or (0x49u shl 16) or (0x46u shl 8) or 0x46u
    private val sampleSize: UInt = 0x0001_2340u // 74_560 bytes — arbitrary realistic WAV size

    @Test
    fun roundTripsByteExact() {
        val original = RiffChunkHeader(fourCC = riffTag, chunkSize = sampleSize)

        // Encode into a fresh buffer.
        val buf = BufferFactory.Default.allocate(8)
        RiffChunkHeaderCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(8, buf.position(), "encode wrote exactly 8 bytes")

        // Wire bytes must match RIFF on the wire: 'R','I','F','F', then 4-byte LE size.
        buf.resetForRead()
        val wire =
            byteArrayOf(
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
                buf.readByte(),
            )
        assertEquals(0x52, wire[0].toInt() and 0xFF, "byte 0 = 'R'")
        assertEquals(0x49, wire[1].toInt() and 0xFF, "byte 1 = 'I'")
        assertEquals(0x46, wire[2].toInt() and 0xFF, "byte 2 = 'F'")
        assertEquals(0x46, wire[3].toInt() and 0xFF, "byte 3 = 'F'")
        assertEquals(0x40, wire[4].toInt() and 0xFF, "byte 4 = LE low byte of 0x0001_2340")
        assertEquals(0x23, wire[5].toInt() and 0xFF, "byte 5")
        assertEquals(0x01, wire[6].toInt() and 0xFF, "byte 6")
        assertEquals(0x00, wire[7].toInt() and 0xFF, "byte 7 = LE high byte (zero)")

        // Decode back and compare.
        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = RiffChunkHeaderCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsAlwaysExact8() {
        val sizes = listOf(0u, 1u, 0x12345678u, 0xFFFFFFFFu)
        for (size in sizes) {
            val v = RiffChunkHeader(fourCC = riffTag, chunkSize = size)
            assertEquals(WireSize.Exact(8), RiffChunkHeaderCodec.wireSize(v, EncodeContext.Empty))
        }
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        // Build the on-wire form (8 bytes) once.
        val encoded = BufferFactory.Default.allocate(8)
        RiffChunkHeaderCodec.encode(encoded, RiffChunkHeader(riffTag, sampleSize), EncodeContext.Empty)
        encoded.resetForRead()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // 0 bytes: NeedsMoreData
            assertEquals(PeekResult.NeedsMoreData, RiffChunkHeaderCodec.peekFrameSize(stream))

            // Drip-feed 7 bytes one at a time. Each call should still report NeedsMoreData.
            for (i in 0 until 7) {
                val oneByte = BufferFactory.Default.allocate(1)
                oneByte.writeByte(encoded.readByte())
                oneByte.resetForRead()
                stream.append(oneByte)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    RiffChunkHeaderCodec.peekFrameSize(stream),
                    "after $i+1 bytes",
                )
            }

            // 8th byte arrives — Complete(8).
            val lastByte = BufferFactory.Default.allocate(1)
            lastByte.writeByte(encoded.readByte())
            lastByte.resetForRead()
            stream.append(lastByte)
            assertEquals(PeekResult.Complete(8), RiffChunkHeaderCodec.peekFrameSize(stream))

            // Verify decode through the stream's readBufferScoped matches the round-trip path.
            val decoded =
                stream.readBufferScoped(8) {
                    RiffChunkHeaderCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(RiffChunkHeader(riffTag, sampleSize), decoded)
            assertEquals(0, stream.available(), "stream should be drained")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeRespectsBaseOffset() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // Push 12 bytes — that's 8 bytes at baseOffset 0 OR at baseOffset 4.
            val twelve = BufferFactory.Default.allocate(12)
            for (i in 0 until 12) twelve.writeByte(i.toByte())
            twelve.resetForRead()
            stream.append(twelve)

            // baseOffset = 0 → 12 bytes available, Complete(8)
            assertEquals(PeekResult.Complete(8), RiffChunkHeaderCodec.peekFrameSize(stream, baseOffset = 0))
            // baseOffset = 4 → 8 bytes available past offset 4, Complete(8)
            assertEquals(PeekResult.Complete(8), RiffChunkHeaderCodec.peekFrameSize(stream, baseOffset = 4))
            // baseOffset = 5 → only 7 bytes available past offset 5, NeedsMoreData
            assertEquals(PeekResult.NeedsMoreData, RiffChunkHeaderCodec.peekFrameSize(stream, baseOffset = 5))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun wraparoundSizesEncodeAndDecodeIntact() {
        // RIFF allows 4 GiB chunks (UInt). Verify the codec round-trips the high-bit-set range.
        val largeSize = 0x8000_0000u // 2 GiB — high bit set, would be a negative Int
        val original = RiffChunkHeader(riffTag, largeSize)
        val buf = BufferFactory.Default.allocate(8)
        RiffChunkHeaderCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = RiffChunkHeaderCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertTrue(decoded.chunkSize > Int.MAX_VALUE.toUInt(), "chunkSize survived as UInt past Int.MAX_VALUE")
    }
}
