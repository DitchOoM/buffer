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
 * Type-check vector. Validates that the hand-emitted
 * [WavFmtChunkCodec] round-trips byte-exact, that the body codec sees a
 * `setLimit`-bounded view of the buffer (so `remaining()` matches the
 * `@LengthPrefixed` resolved length), that the outer limit is restored
 * after the body codec returns (so a chunk embedded in an enclosing
 * RIFF LIST is composable), and that `peekFrameSize` walks
 * `NeedsMoreData → Complete(8 + prefix)`.
 *
 * Wire vector — a PCM `fmt ` chunk:
 *   bytes [f, m, t, ' ', prefix[0..3], body[0..15]]
 *   FourCC = 'fmt ' = 0x66_6D_74_20
 *   prefix = 16 (LE) — the body's wire size
 *   body = audioFormat=1, numChannels=2, sampleRate=44100,
 *          byteRate=176400, blockAlign=4, bitsPerSample=16
 */
class WavFmtChunkCodecTest {
    private val fmtTag: UInt = (0x66u shl 24) or (0x6Du shl 16) or (0x74u shl 8) or 0x20u
    private val pcmBody =
        WavFmtBody(
            audioFormat = 1u,
            numChannels = 2u,
            sampleRate = 44_100u,
            byteRate = 176_400u,
            blockAlign = 4u,
            bitsPerSample = 16u,
        )
    private val pcmBodyWireSize = 16
    private val pcmBodyWire =
        byteArrayOf(
            // audioFormat = 1 LE
            0x01,
            0x00,
            // numChannels = 2 LE
            0x02,
            0x00,
            // sampleRate = 44100 (0x0000AC44) LE
            0x44,
            0xAC.toByte(),
            0x00,
            0x00,
            // byteRate = 176400 (0x0002B110) LE
            0x10,
            0xB1.toByte(),
            0x02,
            0x00,
            // blockAlign = 4 LE
            0x04,
            0x00,
            // bitsPerSample = 16 LE
            0x10,
            0x00,
        )

    private fun makeChunk(): WavFmtChunk =
        WavFmtChunk(
            fourCC = fmtTag,
            body = pcmBody,
        )

    @Test
    fun wavFmtChunkHasTwoConstructorParameters() {
        // Locks the impossible-state-class fix: `chunkSize` is no longer a
        // constructor parameter — the body's bound is expressed by
        // `@LengthPrefixed` on the body field, not by an independent
        // length carrier that could disagree with `body.wireSize()`. Slice
        // 4 redesign-2 ( R3 widening) requires arity == 2.
        //
        // Compile-time lock: pinning `::WavFmtChunk` to a 2-parameter
        // function type. If a third constructor parameter is reintroduced
        // (or one is removed), the constructor reference no longer
        // matches and this assignment fails to compile — strictly tighter
        // than a runtime reflection check, and works in commonTest
        // without `kotlin-reflect` on the classpath.
        @Suppress("UNUSED_VARIABLE")
        val arityLock: (UInt, WavFmtBody) -> WavFmtChunk = ::WavFmtChunk
        // Sanity: destructuring confirms exactly two components are
        // accessible at runtime.
        val (fourCC, body) = makeChunk()
        assertEquals(fmtTag, fourCC)
        assertEquals(pcmBody, body)
    }

    @Test
    fun roundTripsByteExact() {
        val original = makeChunk()
        val totalSize = 8 + pcmBodyWireSize

        // Encode into a fresh buffer.
        val buf = BufferFactory.Default.allocate(totalSize)
        WavFmtChunkCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(totalSize, buf.position(), "encode wrote exactly $totalSize bytes")

        // Validate wire bytes: 'f','m','t',' ', LE size = 16, then PCM body.
        buf.resetForRead()
        val wire = ByteArray(totalSize) { buf.readByte() }
        assertEquals(0x66, wire[0].toInt() and 0xFF, "byte 0 = 'f'")
        assertEquals(0x6D, wire[1].toInt() and 0xFF, "byte 1 = 'm'")
        assertEquals(0x74, wire[2].toInt() and 0xFF, "byte 2 = 't'")
        assertEquals(0x20, wire[3].toInt() and 0xFF, "byte 3 = ' '")
        assertEquals(0x10, wire[4].toInt() and 0xFF, "byte 4 = LE low byte of size 16")
        assertEquals(0x00, wire[5].toInt() and 0xFF, "byte 5")
        assertEquals(0x00, wire[6].toInt() and 0xFF, "byte 6")
        assertEquals(0x00, wire[7].toInt() and 0xFF, "byte 7")
        for (i in pcmBodyWire.indices) {
            assertEquals(pcmBodyWire[i].toInt() and 0xFF, wire[8 + i].toInt() and 0xFF, "body byte $i")
        }

        // Decode back and round-trip.
        val decodeBuf = BufferFactory.Default.wrap(wire)
        val decoded = WavFmtChunkCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(original, decoded, "decoded chunk equals original")

        // Re-encode and verify wire equality (idempotent under repeated encode —
        // a typed body has no source-buffer position to drain, so this is
        // free for `WavFmtChunkCodec`, unlike a buffer-bearing body).
        val reBuf = BufferFactory.Default.allocate(totalSize)
        WavFmtChunkCodec.encode(reBuf, decoded, EncodeContext.Empty)
        reBuf.resetForRead()
        val reWire = ByteArray(totalSize) { reBuf.readByte() }
        assertTrue(wire.contentEquals(reWire), "decode → encode produces byte-identical wire bytes")
    }

    @Test
    fun decodeRestoresOuterLimitAfterBody() {
        // Compose: chunk + 3 trailing bytes. The decode must consume only the
        // chunk's bytes (8 header + chunkSize body), leaving the outer limit
        // unchanged so subsequent reads see the trailing bytes — this is the
        // lock #2 contract for the composition case (RIFF chunk
        // inside a RIFF LIST).
        val totalSize = 8 + pcmBodyWireSize
        val composite = BufferFactory.Default.allocate(totalSize + 3)
        WavFmtChunkCodec.encode(composite, makeChunk(), EncodeContext.Empty)
        composite.writeByte(0x11)
        composite.writeByte(0x22)
        composite.writeByte(0x33)
        composite.resetForRead()

        val outerLimitBefore = composite.limit()
        val decoded = WavFmtChunkCodec.decode(composite, DecodeContext.Empty)
        assertEquals(outerLimitBefore, composite.limit(), "outer limit restored after body decode")
        assertEquals(totalSize, composite.position(), "position advanced past header + body only")
        assertEquals(makeChunk(), decoded)

        // Trailing bytes survive decode.
        assertEquals(0x11.toByte(), composite.readByte())
        assertEquals(0x22.toByte(), composite.readByte())
        assertEquals(0x33.toByte(), composite.readByte())
    }

    @Test
    fun decodeFailsWhenChunkSizeUnderBoundsTheBody() {
        // Build a buffer with chunkSize advertised as 8 but a 16-byte body
        // following — the parent codec's setLimit must bound to 8 so that
        // WavFmtBodyCodec underflows when it tries to read its 16th byte.
        // Validates the bound is *applied*, not just declared: without
        // setLimit, the body codec would happily read 16 bytes and silently
        // mis-frame the next message in an enclosing RIFF LIST.
        val malformed = BufferFactory.Default.allocate(8 + pcmBodyWireSize + 8)
        // header: fmt ', LE size = 8 (advertise too-small)
        malformed.writeByte(0x66)
        malformed.writeByte(0x6D)
        malformed.writeByte(0x74)
        malformed.writeByte(0x20)
        malformed.writeByte(0x08)
        malformed.writeByte(0x00)
        malformed.writeByte(0x00)
        malformed.writeByte(0x00)
        // 16 bytes of body content (more than chunkSize advertises)
        for (b in pcmBodyWire) malformed.writeByte(b)
        // 8 bytes of trailing garbage so the underlying buffer has data —
        // proves the failure comes from the *bound*, not from end-of-buffer
        for (i in 0 until 8) malformed.writeByte(0xEE.toByte())
        malformed.resetForRead()

        assertFailsWith<Throwable> {
            WavFmtChunkCodec.decode(malformed, DecodeContext.Empty)
        }
    }

    @Test
    fun wireSizeIsExactSumOfHeaderAndBody() {
        assertEquals(
            WireSize.Exact(8 + pcmBodyWireSize),
            WavFmtChunkCodec.wireSize(makeChunk(), EncodeContext.Empty),
            "wireSize is Exact(8 + 16) for a PCM fmt chunk",
        )
        // Lock #3 — body codec independently reports Exact, parent
        // sums to Exact, framework takes the pool.withBuffer fast path.
        assertEquals(
            WireSize.Exact(pcmBodyWireSize),
            WavFmtBodyCodec.wireSize(pcmBody, EncodeContext.Empty),
            "body wireSize is Exact(16)",
        )
    }

    @Test
    fun peekFrameSizeWalksNeedsMoreDataToComplete() {
        val pool = BufferPool()
        // Build the on-wire form (8 header + 16 body) once.
        val totalSize = 8 + pcmBodyWireSize
        val encoded = BufferFactory.Default.allocate(totalSize)
        WavFmtChunkCodec.encode(encoded, makeChunk(), EncodeContext.Empty)
        encoded.resetForRead()

        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            // 0 bytes: NeedsMoreData (header not even partially present)
            assertEquals(PeekResult.NeedsMoreData, WavFmtChunkCodec.peekFrameSize(stream))

            // Drip-feed bytes one at a time; every state below totalSize should
            // be NeedsMoreData (header reads at 8, body completes at 8+16).
            for (i in 0 until totalSize - 1) {
                val oneByte = BufferFactory.Default.allocate(1)
                oneByte.writeByte(encoded.readByte())
                oneByte.resetForRead()
                stream.append(oneByte)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    WavFmtChunkCodec.peekFrameSize(stream),
                    "after ${i + 1} bytes",
                )
            }

            // Final byte arrives — Complete(totalSize).
            val lastByte = BufferFactory.Default.allocate(1)
            lastByte.writeByte(encoded.readByte())
            lastByte.resetForRead()
            stream.append(lastByte)
            assertEquals(PeekResult.Complete(totalSize), WavFmtChunkCodec.peekFrameSize(stream))

            // Drain via readBufferScoped, exercising the streaming-loop path.
            val decoded =
                stream.readBufferScoped(totalSize) {
                    WavFmtChunkCodec.decode(this, DecodeContext.Empty)
                }
            assertEquals(makeChunk(), decoded)
            assertEquals(0, stream.available(), "stream drained after frame consumed")
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeRespectsBaseOffsetForBackToBackChunks() {
        // Two back-to-back chunks, each 24 bytes (8 header + 16 body).
        // Verifies baseOffset shifts BOTH (a) the chunkSize peek address and
        // (b) the available()-baseOffset comparison, by pointing at the second
        // chunk via baseOffset=24.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val frameSize = 8 + pcmBodyWireSize
            val twoChunks = BufferFactory.Default.allocate(frameSize * 2)
            WavFmtChunkCodec.encode(twoChunks, makeChunk(), EncodeContext.Empty)
            WavFmtChunkCodec.encode(twoChunks, makeChunk(), EncodeContext.Empty)
            twoChunks.resetForRead()
            stream.append(twoChunks)

            assertEquals(
                PeekResult.Complete(frameSize),
                WavFmtChunkCodec.peekFrameSize(stream, baseOffset = 0),
                "baseOffset=0 lands on first chunk header",
            )
            assertEquals(
                PeekResult.Complete(frameSize),
                WavFmtChunkCodec.peekFrameSize(stream, baseOffset = frameSize),
                "baseOffset=$frameSize lands on second chunk header",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun peekFrameSizeReturnsNeedsMoreDataWhenBaseOffsetLeavesPartialFrame() {
        // Pre-load: full first chunk + first (frameSize - 1) bytes of second
        // chunk. At baseOffset=frameSize the second chunk's header is
        // readable, chunkSize peeks as 16, total=24, but available-frameSize
        // is one short.
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            val frameSize = 8 + pcmBodyWireSize
            val scratch = BufferFactory.Default.allocate(frameSize * 2)
            WavFmtChunkCodec.encode(scratch, makeChunk(), EncodeContext.Empty)
            WavFmtChunkCodec.encode(scratch, makeChunk(), EncodeContext.Empty)
            scratch.resetForRead()
            val truncatedSize = frameSize * 2 - 1
            val truncated = BufferFactory.Default.allocate(truncatedSize)
            for (i in 0 until truncatedSize) truncated.writeByte(scratch.readByte())
            truncated.resetForRead()
            stream.append(truncated)

            assertEquals(
                PeekResult.Complete(frameSize),
                WavFmtChunkCodec.peekFrameSize(stream, baseOffset = 0),
                "first chunk still fully framed",
            )
            assertEquals(
                PeekResult.NeedsMoreData,
                WavFmtChunkCodec.peekFrameSize(stream, baseOffset = frameSize),
                "second chunk header readable but body 1 byte short",
            )
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Test
    fun decodeOfChunkSizeAboveIntMaxThrowsDecodeException() {
        // Lock #4: chunkSize > Int.MAX_VALUE cannot be honored as a
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
            WavFmtChunkCodec.decode(buf, DecodeContext.Empty)
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
                WavFmtChunkCodec.peekFrameSize(stream)
            }
        } finally {
            stream.release()
            pool.clear()
        }
    }
}
