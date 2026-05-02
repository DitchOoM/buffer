package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * Hand-written codec referenced by `@UseCodec` on [WavFmtChunk.body].
 *
 * Reads the six little-endian fields of a PCM WAV `fmt ` body — 16
 * bytes total. Hand-emitted as part of the slice-4 type-check; KSP will
 * emit structurally identical code for `@UseCodec`-referenced object
 * codecs once the Stage A–H pipeline is complete.
 *
 * Reports `WireSize.Exact(16)`. Slice 4 lock #3 requires the body codec
 * for a `@LengthFrom`-bounded field to report `Exact` so the parent's
 * `wireSize` sums to `Exact` and the framework takes the
 * `pool.withBuffer` fast path. The processor is supposed to validate
 * this at compile time — slice 4 is the proof that the resulting
 * generated codec compiles and round-trips against the runtime APIs.
 *
 * `peekFrameSize` stays the default `NoFraming` — this codec is only
 * ever invoked by [WavFmtChunkCodec]'s slice-bounded decode, never as
 * a stream root.
 */
object WavFmtBodyCodec : Codec<WavFmtBody> {
    /** Wire size of a PCM WAV `fmt ` body — fixed 16 bytes. */
    private const val WIRE_SIZE = 16

    /**
     * Reads 16 bytes — six little-endian fields. Manual byte assembly
     * keeps the codec independent of the parent buffer's byte order
     * (`@ProtocolMessage(wireOrder = Endianness.Little)` is a property
     * of the message, not of the runtime buffer the codec writes into).
     */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): WavFmtBody {
        val audioFormat = readLeUShort(buffer)
        val numChannels = readLeUShort(buffer)
        val sampleRate = readLeUInt(buffer)
        val byteRate = readLeUInt(buffer)
        val blockAlign = readLeUShort(buffer)
        val bitsPerSample = readLeUShort(buffer)
        return WavFmtBody(audioFormat, numChannels, sampleRate, byteRate, blockAlign, bitsPerSample)
    }

    /** Writes 16 bytes — six little-endian fields. */
    override fun encode(
        buffer: WriteBuffer,
        value: WavFmtBody,
        context: EncodeContext,
    ) {
        writeLeUShort(buffer, value.audioFormat)
        writeLeUShort(buffer, value.numChannels)
        writeLeUInt(buffer, value.sampleRate)
        writeLeUInt(buffer, value.byteRate)
        writeLeUShort(buffer, value.blockAlign)
        writeLeUShort(buffer, value.bitsPerSample)
    }

    /** Always 16 — body is fixed-size, so the wireSize is `Exact`. */
    override fun wireSize(
        value: WavFmtBody,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(WIRE_SIZE)

    private fun readLeUShort(buffer: ReadBuffer): UShort {
        val b0 = buffer.readUByte().toUInt()
        val b1 = buffer.readUByte().toUInt()
        return (b0 or (b1 shl 8)).toUShort()
    }

    private fun readLeUInt(buffer: ReadBuffer): UInt {
        val b0 = buffer.readUByte().toUInt()
        val b1 = buffer.readUByte().toUInt()
        val b2 = buffer.readUByte().toUInt()
        val b3 = buffer.readUByte().toUInt()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun writeLeUShort(
        buffer: WriteBuffer,
        value: UShort,
    ) {
        val v = value.toUInt()
        buffer.writeUByte((v and 0xFFu).toUByte())
        buffer.writeUByte(((v shr 8) and 0xFFu).toUByte())
    }

    private fun writeLeUInt(
        buffer: WriteBuffer,
        value: UInt,
    ) {
        buffer.writeUByte((value and 0xFFu).toUByte())
        buffer.writeUByte(((value shr 8) and 0xFFu).toUByte())
        buffer.writeUByte(((value shr 16) and 0xFFu).toUByte())
        buffer.writeUByte(((value shr 24) and 0xFFu).toUByte())
    }
}
