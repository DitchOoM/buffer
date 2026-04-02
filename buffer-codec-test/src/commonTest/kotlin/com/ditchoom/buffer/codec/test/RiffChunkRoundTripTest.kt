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
import com.ditchoom.buffer.reverseBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RIFF container format tests.
 * Validates 4-byte ASCII chunk ID dispatch with spec-compliant WAV chunks.
 * Size and numeric fields use little-endian byte order per the RIFF spec.
 */
class RiffChunkRoundTripTest {
    // ========== Chunk ID constants ==========

    @Test
    fun chunkIdFmtValue() {
        assertEquals(0x666D7420, RiffChunkId.FMT.id)
    }

    @Test
    fun chunkIdDataValue() {
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
                byteRate = 176400u,
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

    // ========== Verify exact wire bytes (spec-compliant LE for size/numeric fields) ==========

    @Test
    fun fmtExactWireBytes() {
        val original = RiffChunk.Fmt(16u, 1u, 1u, 22050u, 44100u, 2u, 16u)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        RiffChunkFmtCodec.encode(buffer, original)

        // chunkSize=16 in LE: 10 00 00 00
        assertEquals(0x10.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x00.toByte(), buffer[3])

        // audioFormat=1 in LE: 01 00
        assertEquals(0x01.toByte(), buffer[4])
        assertEquals(0x00.toByte(), buffer[5])

        // numChannels=1 in LE: 01 00
        assertEquals(0x01.toByte(), buffer[6])
        assertEquals(0x00.toByte(), buffer[7])

        // sampleRate=22050 in LE: 22 56 00 00
        assertEquals(0x22.toByte(), buffer[8])
        assertEquals(0x56.toByte(), buffer[9])
        assertEquals(0x00.toByte(), buffer[10])
        assertEquals(0x00.toByte(), buffer[11])

        assertEquals(20, buffer.position()) // 4+2+2+4+4+2+2 = 20 bytes
    }

    @Test
    fun factExactWireBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        RiffChunkFactCodec.encode(buffer, RiffChunk.Fact(4u, 44100u))

        // chunkSize=4 in LE: 04 00 00 00
        assertEquals(0x04.toByte(), buffer[0])
        assertEquals(0x00.toByte(), buffer[1])
        assertEquals(0x00.toByte(), buffer[2])
        assertEquals(0x00.toByte(), buffer[3])

        // sampleCount=44100 in LE: 44 AC 00 00
        assertEquals(0x44.toByte(), buffer[4])
        assertEquals(0xAC.toByte(), buffer[5])
        assertEquals(0x00.toByte(), buffer[6])
        assertEquals(0x00.toByte(), buffer[7])
    }

    // ========== Decode from spec-compliant wire bytes ==========

    @Test
    fun dispatchDecodesFmtFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        // Chunk ID "fmt " as BE (dispatch discriminator)
        buffer.writeInt(0x666D7420)
        // All subsequent fields are LE per RIFF spec
        buffer.writeInt(16.reverseBytes()) // chunkSize=16 LE
        buffer.writeShort(1.toShort().reverseBytes()) // audioFormat=PCM LE
        buffer.writeShort(1.toShort().reverseBytes()) // numChannels=mono LE
        buffer.writeInt(22050.reverseBytes()) // sampleRate LE
        buffer.writeInt(44100.reverseBytes()) // byteRate LE
        buffer.writeShort(2.toShort().reverseBytes()) // blockAlign LE
        buffer.writeShort(16.toShort().reverseBytes()) // bitsPerSample LE
        buffer.resetForRead()

        val decoded = RiffChunkCodec.decode(buffer)
        assertTrue(decoded is RiffChunk.Fmt)
        assertEquals(16u, decoded.chunkSize)
        assertEquals(1u.toUShort(), decoded.audioFormat)
        assertEquals(1u.toUShort(), decoded.numChannels)
        assertEquals(22050u, decoded.sampleRate)
        assertEquals(16u.toUShort(), decoded.bitsPerSample)
    }

    @Test
    fun dispatchDecodesFactFromSpecBytes() {
        val buffer = BufferFactory.Default.allocate(32, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x66616374) // "fact" BE
        buffer.writeInt(4.reverseBytes()) // chunkSize=4 LE
        buffer.writeInt(44100.reverseBytes()) // sampleCount LE
        buffer.resetForRead()

        val decoded = RiffChunkCodec.decode(buffer)
        assertTrue(decoded is RiffChunk.Fact)
        assertEquals(4u, decoded.chunkSize)
        assertEquals(44100u, decoded.sampleCount)
    }

    @Test
    fun dispatchUnknownChunkIdThrows() {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        buffer.writeInt(0x4C495354) // "LIST" — not registered
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> { RiffChunkCodec.decode(buffer) }
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
        // First 4 bytes should be "fact" = 0x66616374 (BE chunk ID)
        assertEquals(0x66.toByte(), buffer[0]) // 'f'
        assertEquals(0x61.toByte(), buffer[1]) // 'a'
        assertEquals(0x63.toByte(), buffer[2]) // 'c'
        assertEquals(0x74.toByte(), buffer[3]) // 't'
        // Bytes 4-7 should be chunkSize=4 in LE: 04 00 00 00
        assertEquals(0x04.toByte(), buffer[4])
        assertEquals(0x00.toByte(), buffer[5])
    }
}
