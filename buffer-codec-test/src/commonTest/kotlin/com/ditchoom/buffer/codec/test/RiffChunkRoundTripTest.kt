package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.RiffChunk
import com.ditchoom.buffer.codec.test.protocols.RiffChunkCodec
import com.ditchoom.buffer.codec.test.protocols.RiffChunkDataCodec
import com.ditchoom.buffer.codec.test.protocols.RiffChunkFactCodec
import com.ditchoom.buffer.codec.test.protocols.RiffChunkFmtCodec
import com.ditchoom.buffer.codec.test.protocols.RiffChunkId
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RIFF container format tests.
 * Validates 4-byte ASCII chunk ID dispatch with spec-compliant WAV chunks.
 */
class RiffChunkRoundTripTest {
    // ========== Chunk ID constants ==========

    @Test
    fun chunkIdFmtValue() {
        // "fmt " = 0x666D7420
        assertEquals(0x666D7420, RiffChunkId.FMT.id)
    }

    @Test
    fun chunkIdDataValue() {
        // "data" = 0x64617461
        assertEquals(0x64617461, RiffChunkId.DATA.id)
    }

    // ========== Sub-codec round-trips ==========

    @Test
    fun fmtChunkRoundTrip() {
        val original =
            RiffChunk.Fmt(
                chunkSize = 16u,
                audioFormat = 1u, // PCM
                numChannels = 2u, // stereo
                sampleRate = 44100u,
                byteRate = 176400u, // sampleRate * numChannels * bitsPerSample/8
                blockAlign = 4u,
                bitsPerSample = 16u,
            )
        val decoded = RiffChunkFmtCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun factChunkRoundTrip() {
        val original = RiffChunk.Fact(chunkSize = 4u, sampleCount = 88200u)
        val decoded = RiffChunkFactCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun dataChunkRoundTrip() {
        val original = RiffChunk.Data(chunkSize = 5u, samples = "audio")
        val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
        RiffChunkDataCodec.encode(buffer, original) { buf, s -> buf.writeString(s) }
        buffer.resetForRead()
        val decoded = RiffChunkDataCodec.decode<String>(buffer) { pr -> pr.readString(pr.remaining()) }
        assertEquals(5u, decoded.chunkSize)
        assertEquals("audio", decoded.samples)
    }

    // ========== Dispatch decode from spec bytes ==========

    @Test
    fun dispatchDecodesFmtFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        // Write "fmt " as 4-byte big-endian: 0x666D7420
        buffer.writeInt(0x666D7420)
        buffer.writeInt(16) // chunkSize (would be LE in real RIFF, but codec uses buffer's byte order)
        buffer.writeShort(1.toShort()) // PCM
        buffer.writeShort(1.toShort()) // mono
        buffer.writeInt(22050) // sampleRate
        buffer.writeInt(44100) // byteRate
        buffer.writeShort(2.toShort()) // blockAlign
        buffer.writeShort(16.toShort()) // bitsPerSample
        buffer.resetForRead()

        val decoded = RiffChunkCodec.decode(buffer)
        assertTrue(decoded is RiffChunk.Fmt)
        assertEquals(1u.toUShort(), decoded.audioFormat)
        assertEquals(1u.toUShort(), decoded.numChannels)
        assertEquals(22050u, decoded.sampleRate)
        assertEquals(16u.toUShort(), decoded.bitsPerSample)
    }

    @Test
    fun dispatchDecodesFactFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x66616374) // "fact"
        buffer.writeInt(4) // chunkSize
        buffer.writeInt(44100) // sampleCount
        buffer.resetForRead()

        val decoded = RiffChunkCodec.decode(buffer)
        assertTrue(decoded is RiffChunk.Fact)
        assertEquals(44100u, decoded.sampleCount)
    }

    @Test
    fun dispatchUnknownChunkIdThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x4C495354) // "LIST" — not registered
        buffer.resetForRead()

        assertFailsWith<IllegalArgumentException> {
            RiffChunkCodec.decode(buffer)
        }
    }

    // ========== Dispatch round-trip ==========

    @Test
    fun fmtDispatchRoundTrip() {
        val original: RiffChunk = RiffChunk.Fmt(16u, 1u, 2u, 48000u, 192000u, 4u, 16u)
        val decoded = RiffChunkCodec.testRoundTrip(original)
        assertTrue(decoded is RiffChunk.Fmt)
        assertEquals(original, decoded)
    }

    @Test
    fun factDispatchRoundTrip() {
        val original: RiffChunk = RiffChunk.Fact(4u, 96000u)
        val decoded = RiffChunkCodec.testRoundTrip(original)
        assertTrue(decoded is RiffChunk.Fact)
        assertEquals(original, decoded)
    }

    @Test
    fun encodeWritesCorrectChunkIdBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        RiffChunkCodec.encode(buffer, RiffChunk.Fact(4u, 0u))
        // First 4 bytes should be "fact" = 0x66616374
        assertEquals(0x66.toByte(), buffer[0]) // 'f'
        assertEquals(0x61.toByte(), buffer[1]) // 'a'
        assertEquals(0x63.toByte(), buffer[2]) // 'c'
        assertEquals(0x74.toByte(), buffer[3]) // 't'
    }
}
