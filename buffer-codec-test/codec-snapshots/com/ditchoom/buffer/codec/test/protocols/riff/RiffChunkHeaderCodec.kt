package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RiffChunkHeaderCodec : Codec<RiffChunkHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunkHeader {
    val fourCCB0 = buffer.readUByte().toUInt()
    val fourCCB1 = buffer.readUByte().toUInt()
    val fourCCB2 = buffer.readUByte().toUInt()
    val fourCCB3 = buffer.readUByte().toUInt()
    val fourCC = ((fourCCB0 shl 24) or (fourCCB1 shl 16) or (fourCCB2 shl 8) or fourCCB3)
    val chunkSizeB0 = buffer.readUByte().toUInt()
    val chunkSizeB1 = buffer.readUByte().toUInt()
    val chunkSizeB2 = buffer.readUByte().toUInt()
    val chunkSizeB3 = buffer.readUByte().toUInt()
    val chunkSize = (chunkSizeB0 or (chunkSizeB1 shl 8) or (chunkSizeB2 shl 16) or (chunkSizeB3 shl 24))
    return RiffChunkHeader(fourCC = fourCC, chunkSize = chunkSize)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RiffChunkHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.fourCC shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.fourCC shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.fourCC shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.fourCC and 0xFFu).toUByte())
    buffer.writeUByte((value.chunkSize and 0xFFu).toUByte())
    buffer.writeUByte(((value.chunkSize shr 8) and 0xFFu).toUByte())
    buffer.writeUByte(((value.chunkSize shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.chunkSize shr 24) and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: RiffChunkHeader, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
