package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Generated codec for [RiffChunk] — wire-format details below.
 *
 * Hand-written for the Phase 10 slice 4 type-check exercise. KSP will
 * emit structurally identical code once the Stage E emitter handles
 * `@LengthFrom` + `@UseCodec`; this file proves the sketched shape
 * compiles against the real runtime APIs and that the slice-bounding
 * `setLimit` + restore contract round-trips end-to-end.
 *
 * @see RiffChunk
 * @see RawChunkBodyCodec
 *
 * ```text
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +---------------+---------------+---------------+---------------+
 * |     'R'       |     'I'       |     'F'       |     'F'       |  fourCC (4b ASCII)
 * +---------------+---------------+---------------+---------------+
 * |                          chunkSize (4b LE)                    |
 * +---------------------------------------------------------------+
 * | <chunkSize> bytes — body (length resolved via @LengthFrom)    |
 * +---------------------------------------------------------------+
 * ```
 *
 * Source documentation:
 *   One full RIFF chunk: 4-byte FourCC tag, 4-byte LE size, body of
 *   exactly [chunkSize] bytes, then optionally one pad byte to keep the
 *   next chunk 2-byte aligned (the pad is framed by the *enclosing*
 *   RIFF list, not by the chunk itself, so it is not modeled here).
 */
object RiffChunkCodec : Codec<RiffChunk> {
    /**
     * Reads 8 header bytes, then exactly [RiffChunk.chunkSize] body
     * bytes via [RawChunkBodyCodec] over a `setLimit`-bounded slice.
     *
     * The bound + restore pattern is the sync analogue of locked
     * decision #5 (sync = `setLimit`, async = `slice().use { }`). It is
     * the uniform contract for every `@UseCodec`-referenced sync codec
     * inside a length-bounded field — the inner codec sees a buffer
     * whose `remaining()` equals the resolved length.
     */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): RiffChunk {
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

        // Wire: bound the buffer to exactly chunkSize bytes so RawChunkBodyCodec
        // sees remaining() == chunkSize, then restore the outer limit so
        // composition with an enclosing RIFF LIST is preserved.
        val resolvedLength = resolveBodyLength(chunkSize, fieldPath = "RiffChunk.body")
        val outerLimit = buffer.limit()
        buffer.setLimit(buffer.position() + resolvedLength)
        val body =
            try {
                RawChunkBodyCodec.decode(buffer, context)
            } finally {
                buffer.setLimit(outerLimit)
            }
        return RiffChunk(fourCC, chunkSize, body)
    }

    /** Writes 8 header bytes, then the body bytes — wire size = 8 + body wire size. */
    override fun encode(
        buffer: WriteBuffer,
        value: RiffChunk,
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

        RawChunkBodyCodec.encode(buffer, value.body, context)
    }

    /**
     * `Exact(8 + body wire size)`. The body codec reports `Exact` (slice
     * 4 lock #3 — any `@UseCodec` body inside a `@LengthFrom`-bounded
     * field must report `Exact`); the header is fixed at 8 bytes; the
     * sum is therefore exact and the framework takes the
     * `pool.withBuffer` fast path.
     */
    override fun wireSize(
        value: RiffChunk,
        context: EncodeContext,
    ): WireSize {
        val bodySize = (RawChunkBodyCodec.wireSize(value.body, context) as WireSize.Exact).bytes
        return WireSize.Exact(8 + bodySize)
    }

    /**
     * Frame size = 8 + chunkSize. Peeks the 4-byte LE chunkSize at
     * offset 4 and returns `Complete(8 + chunkSize)` once that many
     * bytes are available.
     *
     * Slice 4 lock #4: when the resolved frame size would exceed
     * `Int.MAX_VALUE` (RIFF's UInt range allows up to 4 GiB), throw a
     * typed [DecodeException] from peek rather than wrapping silently.
     * Surfaces malformed inputs at frame detection rather than at
     * decode time.
     */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() - baseOffset < 8) return PeekResult.NeedsMoreData
        // Wire: 4 LE bytes at offset+4 — manual assembly because StreamProcessor
        // exposes byte-level peek only. Mirrors the LE assembly in decode().
        val b0 = stream.peekByte(baseOffset + 4).toInt() and 0xFF
        val b1 = stream.peekByte(baseOffset + 5).toInt() and 0xFF
        val b2 = stream.peekByte(baseOffset + 6).toInt() and 0xFF
        val b3 = stream.peekByte(baseOffset + 7).toInt() and 0xFF
        val chunkSize = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
        val total = peekTotalFrameSize(chunkSize, baseOffset = baseOffset)
        return if (stream.available() - baseOffset >= total) {
            PeekResult.Complete(total)
        } else {
            PeekResult.NeedsMoreData
        }
    }

    /**
     * Decode-time guard for [RiffChunk.chunkSize] → Int conversion. The
     * sync decode bound is `position + len`; both are Ints, so a
     * chunkSize > `Int.MAX_VALUE` cannot be honored. Throws a typed
     * [DecodeException] so callers can distinguish protocol-level
     * overflow from a generic IllegalArgumentException.
     */
    private fun resolveBodyLength(
        chunkSize: UInt,
        fieldPath: String,
    ): Int {
        if (chunkSize > Int.MAX_VALUE.toUInt()) {
            throw DecodeException(
                fieldPath = fieldPath,
                bufferPosition = -1,
                expected = "chunkSize <= ${Int.MAX_VALUE}",
                actual = chunkSize.toString(),
            )
        }
        return chunkSize.toInt()
    }

    /**
     * Peek-time guard for `8 + chunkSize` overflow into a negative
     * Int. Throws so the streaming loop fails fast at frame detection.
     */
    private fun peekTotalFrameSize(
        chunkSize: UInt,
        baseOffset: Int,
    ): Int {
        if (chunkSize > (Int.MAX_VALUE - 8).toUInt()) {
            throw DecodeException(
                fieldPath = "RiffChunk.chunkSize",
                bufferPosition = baseOffset + 4,
                expected = "8 + chunkSize <= ${Int.MAX_VALUE}",
                actual = "8 + $chunkSize",
            )
        }
        return 8 + chunkSize.toInt()
    }
}
