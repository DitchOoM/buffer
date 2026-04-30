package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import kotlin.jvm.JvmInline

/**
 * One full RIFF chunk: 4-byte FourCC tag, 4-byte LE size, body of
 * exactly [chunkSize] bytes, then optionally one pad byte to keep the
 * next chunk 2-byte aligned (the pad is framed by the *enclosing*
 * RIFF list, not by the chunk itself, so it is not modeled here).
 *
 * Slice 4 vector for `@LengthFrom` + `@UseCodec`. The body field is
 * delegated to [RawChunkBodyCodec] over a buffer that the parent codec
 * pre-bounds to exactly `chunkSize` bytes.
 */
@ProtocolMessage(wireOrder = Endianness.Little)
data class RiffChunk(
    val fourCC: UInt,
    val chunkSize: UInt,
    @LengthFrom("chunkSize")
    @UseCodec(RawChunkBodyCodec::class)
    val body: ChunkBody,
)

/**
 * Body of a RIFF chunk — opaque bytes for slice 4. Real codecs dispatch
 * on `fourCC` to a specific body type via [UseCodec]; here the body is
 * an opaque holder so the slice tests `@LengthFrom` routing in
 * isolation.
 *
 * Holds a zero-copy [ReadBuffer] view of the wire bytes; the decoder
 * does not allocate.
 */
@JvmInline
value class ChunkBody(
    val raw: ReadBuffer,
)
