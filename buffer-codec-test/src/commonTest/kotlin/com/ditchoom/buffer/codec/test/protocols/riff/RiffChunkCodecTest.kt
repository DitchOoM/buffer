package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Slice 4 type-check vector. Validates that the hand-emitted
 * [RiffChunkCodec] round-trips byte-exact, that the body codec sees a
 * `setLimit`-bounded view of the buffer (so `remaining()` matches the
 * `@LengthFrom` resolved length), that the outer limit is restored
 * after the body codec returns (so a chunk embedded in an enclosing
 * RIFF LIST is composable), and that `peekFrameSize` walks
 * `NeedsMoreData → Complete(8 + chunkSize)`.
 *
 * Wire vector — a synthetic 'fmt ' chunk with a 4-byte body:
 *   bytes [f, m, t, ' ', size[0..3], body[0..3]]
 *   FourCC = 'fmt ' = 0x66_6D_74_20
 *   chunkSize = 4 (LE)
 *   body = [0xDE, 0xAD, 0xBE, 0xEF]
 */
class RiffChunkCodecTest {
    private val fmtTag: UInt = (0x66u shl 24) or (0x6Du shl 16) or (0x74u shl 8) or 0x20u
    private val bodyBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    private fun makeChunk(): RiffChunk {
        val bodyBuf = BufferFactory.Default.wrap(bodyBytes)
        return RiffChunk(
            fourCC = fmtTag,
            chunkSize = bodyBytes.size.toUInt(),
            body = ChunkBody(bodyBuf),
        )
    }

    @Test
    fun roundTripsByteExact() {
        val original = makeChunk()
        val totalSize = 8 + bodyBytes.size

        // Encode into a fresh buffer.
        val buf = BufferFactory.Default.allocate(totalSize)
        RiffChunkCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(totalSize, buf.position(), "encode wrote exactly $totalSize bytes")

        // Validate wire bytes: 'f','m','t',' ', then 4-byte LE size = 4, then body.
        buf.resetForRead()
        val wire = ByteArray(totalSize) { buf.readByte() }
        assertEquals(0x66, wire[0].toInt() and 0xFF, "byte 0 = 'f'")
        assertEquals(0x6D, wire[1].toInt() and 0xFF, "byte 1 = 'm'")
        assertEquals(0x74, wire[2].toInt() and 0xFF, "byte 2 = 't'")
        assertEquals(0x20, wire[3].toInt() and 0xFF, "byte 3 = ' '")
        assertEquals(0x04, wire[4].toInt() and 0xFF, "byte 4 = LE low byte of size 4")
        assertEquals(0x00, wire[5].toInt() and 0xFF, "byte 5")
        assertEquals(0x00, wire[6].toInt() and 0xFF, "byte 6")
        assertEquals(0x00, wire[7].toInt() and 0xFF, "byte 7")
        for (i in bodyBytes.indices) {
            assertEquals(bodyBytes[i].toInt() and 0xFF, wire[8 + i].toInt() and 0xFF, "body byte $i")
        }

        // Decode back and round-trip.
        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = RiffChunkCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(original.fourCC, decoded.fourCC)
        assertEquals(original.chunkSize, decoded.chunkSize)
        assertEquals(bodyBytes.size, decoded.body.raw.remaining(), "body slice has chunkSize bytes remaining")

        // Re-encode and verify wire equality.
        val reBuf = BufferFactory.Default.allocate(totalSize)
        RiffChunkCodec.encode(reBuf, decoded, EncodeContext.Empty)
        reBuf.resetForRead()
        val reWire = ByteArray(totalSize) { reBuf.readByte() }
        assertTrue(wire.contentEquals(reWire), "decode → encode produces byte-identical wire bytes")
    }

    @Test
    fun decodeRestoresOuterLimitAfterBody() {
        // Compose: 4 bytes of garbage + chunk + 3 trailing bytes. The decode
        // must consume only the chunk's bytes (8 header + chunkSize body),
        // leaving the outer limit unchanged so subsequent reads see the
        // trailing bytes — this is the slice 4 lock #2 contract for the
        // composition case (RIFF chunk inside a RIFF LIST).
        val totalSize = 8 + bodyBytes.size
        val composite = BufferFactory.Default.allocate(totalSize + 3)
        // Trailing bytes that must remain readable after decode.
        // First we encode the chunk, then append a sentinel.
        RiffChunkCodec.encode(composite, makeChunk(), EncodeContext.Empty)
        composite.writeByte(0x11)
        composite.writeByte(0x22)
        composite.writeByte(0x33)
        composite.resetForRead()

        val outerLimitBefore = composite.limit()
        val decoded = RiffChunkCodec.decode(composite, DecodeContext.Empty)
        assertEquals(outerLimitBefore, composite.limit(), "outer limit restored after body decode")
        assertEquals(totalSize, composite.position(), "position advanced past header + body only")
        assertEquals(fmtTag, decoded.fourCC)
        assertEquals(bodyBytes.size.toUInt(), decoded.chunkSize)

        // Trailing bytes survive decode.
        assertEquals(0x11.toByte(), composite.readByte())
        assertEquals(0x22.toByte(), composite.readByte())
        assertEquals(0x33.toByte(), composite.readByte())
    }

    @Test
    fun bodyCodecSeesPreBoundedSlice() {
        // Build a chunk whose body has a deliberately-distinctive marker, then
        // confirm the decoded body slice's remaining() equals chunkSize and
        // its bytes are exactly the body region (proves setLimit bounded the
        // buffer such that remaining() == chunkSize when RawChunkBodyCodec ran).
        val markerBody = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val chunk =
            RiffChunk(
                fourCC = fmtTag,
                chunkSize = markerBody.size.toUInt(),
                body = ChunkBody(BufferFactory.Default.wrap(markerBody)),
            )
        val totalSize = 8 + markerBody.size
        val encoded = BufferFactory.Default.allocate(totalSize)
        RiffChunkCodec.encode(encoded, chunk, EncodeContext.Empty)
        encoded.resetForRead()

        val decoded = RiffChunkCodec.decode(encoded, DecodeContext.Empty)
        val bodySlice = decoded.body.raw
        assertEquals(markerBody.size, bodySlice.remaining(), "body slice remaining == chunkSize")
        for (i in markerBody.indices) {
            assertEquals(markerBody[i], bodySlice.get(bodySlice.position() + i), "body byte $i")
        }
    }

    @Test
    fun wireSizeIsExactSumOfHeaderAndBody() {
        val sizes = listOf(0, 1, 4, 64, 4096)
        for (size in sizes) {
            val body = ByteArray(size) { i -> (i and 0xFF).toByte() }
            val chunk =
                RiffChunk(
                    fourCC = fmtTag,
                    chunkSize = size.toUInt(),
                    body = ChunkBody(BufferFactory.Default.wrap(body)),
                )
            assertEquals(
                WireSize.Exact(8 + size),
                RiffChunkCodec.wireSize(chunk, EncodeContext.Empty),
                "wireSize for body of $size bytes",
            )
        }
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        // Build the on-wire form (8 header + 4 body) once.
        val totalSize = 8 + bodyBytes.size
        val encoded = BufferFactory.Default.allocate(totalSize)
        RiffChunkCodec.encode(encoded, makeChunk(), EncodeContext.Empty)
        encoded.resetForRead()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // 0 bytes: NeedsMoreData (header not even partially present)
            assertEquals(PeekResult.NeedsMoreData, RiffChunkCodec.peekFrameSize(stream))

            // Drip-feed bytes one at a time; every state below totalSize should
            // be NeedsMoreData (header reads at 8, body completes at 8+4).
            for (i in 0 until totalSize - 1) {
                val oneByte = BufferFactory.Default.allocate(1)
                oneByte.writeByte(encoded.readByte())
                oneByte.resetForRead()
                stream.append(oneByte)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    RiffChunkCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }

            // Final byte arrives — Complete(totalSize).
            val lastByte = BufferFactory.Default.allocate(1)
            lastByte.writeByte(encoded.readByte())
            lastByte.resetForRead()
            stream.append(lastByte)
            assertEquals(PeekResult.Complete(totalSize), RiffChunkCodec.peekFrameSize(stream))

            // Drain via readBufferScoped, exercising the streaming-loop path.
            val decoded =
                stream.readBufferScoped(totalSize) {
                    RiffChunkCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(fmtTag, decoded.fourCC)
            assertEquals(bodyBytes.size.toUInt(), decoded.chunkSize)
            assertEquals(0, stream.available(), "stream drained after frame consumed")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeRespectsBaseOffsetForBackToBackChunks() {
        // Two back-to-back chunks, each 12 bytes (8 header + 4 body of zeros).
        // Verifies baseOffset shifts BOTH (a) the chunkSize peek address and
        // (b) the available()-baseOffset comparison, by pointing at the second
        // chunk via baseOffset=12.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val zeroBody = ByteArray(4)
            // Each encode consumes the source body buffer's position, so use
            // a fresh wrap per chunk.
            val chunk1 = RiffChunk(fmtTag, 4u, ChunkBody(BufferFactory.Default.wrap(zeroBody)))
            val chunk2 = RiffChunk(fmtTag, 4u, ChunkBody(BufferFactory.Default.wrap(zeroBody)))
            val twoChunks = BufferFactory.Default.allocate(24)
            RiffChunkCodec.encode(twoChunks, chunk1, EncodeContext.Empty)
            RiffChunkCodec.encode(twoChunks, chunk2, EncodeContext.Empty)
            twoChunks.resetForRead()
            stream.append(twoChunks)

            assertEquals(
                PeekResult.Complete(12),
                RiffChunkCodec.peekFrameSize(stream, baseOffset = 0),
                "baseOffset=0 lands on first chunk header",
            )
            assertEquals(
                PeekResult.Complete(12),
                RiffChunkCodec.peekFrameSize(stream, baseOffset = 12),
                "baseOffset=12 lands on second chunk header",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeReturnsNeedsMoreDataWhenBaseOffsetLeavesPartialFrame() {
        // Pre-load: full first chunk + first 11 bytes of second chunk (1 short
        // of a complete frame). At baseOffset=12 the second chunk's header is
        // readable, chunkSize peeks as 4, total=12, but available-12 = 11 < 12.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val zeroBody = ByteArray(4)
            // Each encode consumes the source body buffer's position, so use
            // a fresh wrap per chunk.
            val chunk1 = RiffChunk(fmtTag, 4u, ChunkBody(BufferFactory.Default.wrap(zeroBody)))
            val chunk2 = RiffChunk(fmtTag, 4u, ChunkBody(BufferFactory.Default.wrap(zeroBody)))
            // Encode two chunks fully into a scratch buffer, then push the
            // first 23 bytes (one full chunk + 11 bytes of the second) to the
            // stream — leaving the final body byte missing.
            val scratch = BufferFactory.Default.allocate(24)
            RiffChunkCodec.encode(scratch, chunk1, EncodeContext.Empty)
            RiffChunkCodec.encode(scratch, chunk2, EncodeContext.Empty)
            scratch.resetForRead()
            val truncated = BufferFactory.Default.allocate(23)
            for (i in 0 until 23) truncated.writeByte(scratch.readByte())
            truncated.resetForRead()
            stream.append(truncated)

            assertEquals(
                PeekResult.Complete(12),
                RiffChunkCodec.peekFrameSize(stream, baseOffset = 0),
                "first chunk still fully framed",
            )
            assertEquals(
                PeekResult.NeedsMoreData,
                RiffChunkCodec.peekFrameSize(stream, baseOffset = 12),
                "second chunk header readable but body 1 byte short",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun decodeOfChunkSizeAboveIntMaxThrowsDecodeException() {
        // Slice 4 lock #4: chunkSize > Int.MAX_VALUE cannot be honored as a
        // setLimit bound (Ints), so decode throws DecodeException early.
        // Build a header by hand: 'fmt ', then chunkSize = 0x80_00_00_00 LE.
        val header =
            byteArrayOf(
                0x66,
                0x6D,
                0x74,
                0x20,
                0x00,
                0x00,
                0x00,
                0x80.toByte(),
            )
        val buf = BufferFactory.Default.wrap(header)
        assertFailsWith<DecodeException> {
            RiffChunkCodec.decode(buf, DecodeContext.Empty)
        }
    }

    @Test
    fun peekFrameSizeOfChunkSizeAboveIntMaxThrowsDecodeException() {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // 8 bytes: 'fmt ' + LE chunkSize = 0xFFFF_FFFF (4 GiB - 1).
            val header = BufferFactory.Default.allocate(8)
            header.writeByte(0x66)
            header.writeByte(0x6D)
            header.writeByte(0x74)
            header.writeByte(0x20)
            header.writeByte(0xFF.toByte())
            header.writeByte(0xFF.toByte())
            header.writeByte(0xFF.toByte())
            header.writeByte(0xFF.toByte())
            header.resetForRead()
            stream.append(header)

            assertFailsWith<DecodeException> {
                RiffChunkCodec.peekFrameSize(stream)
            }
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
