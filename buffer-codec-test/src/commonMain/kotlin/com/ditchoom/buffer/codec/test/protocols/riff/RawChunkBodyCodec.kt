package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * Hand-written codec referenced by `@UseCodec` on [RiffChunk.body].
 *
 * Hand-emitted as part of the slice 4 type-check; KSP will emit
 * structurally identical code for `@UseCodec`-referenced object codecs
 * once the Stage A-H pipeline is complete.
 *
 * Reads `remaining()` bytes — the parent codec bounds the buffer to
 * exactly the resolved `@LengthFrom` length before this codec is
 * called, so this codec's view of "remaining" *is* the body length.
 *
 * Reports `WireSize.Exact(body.raw.remaining())`. Per slice 4's
 * findings the processor should validate at compile time that any
 * `@UseCodec` body inside a `@LengthFrom`-bounded field reports
 * `Exact` so the parent's wire size sums to `Exact` and the bridge
 * takes the fast `pool.withBuffer` path.
 *
 * `peekFrameSize` stays the default `NoFraming` — this codec is only
 * ever invoked by [RiffChunkCodec]'s slice-bounded decode, never as a
 * stream root.
 */
object RawChunkBodyCodec : Codec<ChunkBody> {
    /** Reads `buffer.remaining()` bytes as a zero-copy slice. */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ChunkBody = ChunkBody(buffer.readBytes(buffer.remaining()))

    /** Bulk-copies the body's wire bytes into the destination. */
    override fun encode(
        buffer: WriteBuffer,
        value: ChunkBody,
        context: EncodeContext,
    ) {
        buffer.write(value.raw)
    }

    /** Always `Exact` — body length is the wrapped slice's remaining count. */
    override fun wireSize(
        value: ChunkBody,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.raw.remaining())
}
