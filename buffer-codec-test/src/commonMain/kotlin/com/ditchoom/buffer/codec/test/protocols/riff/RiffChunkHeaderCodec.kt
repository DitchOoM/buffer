package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Generated codec for [RiffChunkHeader] — wire-format details below.
 *
 * Hand-written for the Phase 10 type-check exercise. KSP will emit
 * structurally identical code once the Stage A emitter lands; this
 * file proves the sketched shape compiles against the real runtime
 * APIs (rather than the API-name fictions that the slice sketches
 * in PHASE_10_DESIGN_NOTES.md guessed at).
 *
 * @see RiffChunkHeader
 *
 * Source documentation:
 *   RIFF chunk header — 4-byte ASCII FourCC tag followed by a 4-byte
 *   little-endian unsigned size. Independent of the body that follows
 *   it on the wire; see [RiffChunk] for the framed view that reads
 *   `chunkSize` bytes of body after the header.
 *
 *   Slice 1 vector for `@ProtocolMessage`. Validates: fixed-layout
 *   two-field message with mixed effective endianness (FourCC bytes
 *   are positional ASCII, exposed as a numeric tag for matching;
 *   `chunkSize` is little-endian per the RIFF 1.0 spec).
 */
object RiffChunkHeaderCodec : Codec<RiffChunkHeader> {
    /** Reads 8 bytes — fourCC then little-endian chunkSize. */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): RiffChunkHeader {
        // Wire: FourCC bytes are positional ASCII — read as a 4-byte tag in
        // wire order (high byte first regardless of buffer endianness)
        val b0 = buffer.readUByte().toUInt()
        val b1 = buffer.readUByte().toUInt()
        val b2 = buffer.readUByte().toUInt()
        val b3 = buffer.readUByte().toUInt()
        val fourCC = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3

        // Wire: 4 LE bytes for chunkSize — assemble little-end-first
        val s0 = buffer.readUByte().toUInt()
        val s1 = buffer.readUByte().toUInt()
        val s2 = buffer.readUByte().toUInt()
        val s3 = buffer.readUByte().toUInt()
        val chunkSize = s0 or (s1 shl 8) or (s2 shl 16) or (s3 shl 24)
        return RiffChunkHeader(fourCC, chunkSize)
    }

    /** Writes 8 bytes — fourCC then little-endian chunkSize. */
    override fun encode(
        buffer: WriteBuffer,
        value: RiffChunkHeader,
        context: EncodeContext,
    ) {
        // Wire: FourCC bytes are positional ASCII — emit high byte first
        buffer.writeUByte(((value.fourCC shr 24) and 0xFFu).toUByte())
        buffer.writeUByte(((value.fourCC shr 16) and 0xFFu).toUByte())
        buffer.writeUByte(((value.fourCC shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((value.fourCC and 0xFFu).toUByte())

        // Wire: 4 LE bytes for chunkSize — emit low byte first
        buffer.writeUByte((value.chunkSize and 0xFFu).toUByte())
        buffer.writeUByte(((value.chunkSize shr 8) and 0xFFu).toUByte())
        buffer.writeUByte(((value.chunkSize shr 16) and 0xFFu).toUByte())
        buffer.writeUByte(((value.chunkSize shr 24) and 0xFFu).toUByte())
    }

    /** Always 8 bytes. */
    override fun wireSize(
        value: RiffChunkHeader,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(8)

    /** Always 8 bytes — peek succeeds the moment 8 bytes are buffered. */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult =
        if (stream.available() - baseOffset >= 8) {
            PeekResult.Complete(8)
        } else {
            PeekResult.NeedsMoreData
        }
}
