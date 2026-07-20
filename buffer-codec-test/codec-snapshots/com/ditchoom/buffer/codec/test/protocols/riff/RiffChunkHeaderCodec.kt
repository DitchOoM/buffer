package com.ditchoom.buffer.codec.test.protocols.riff

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object RiffChunkHeaderCodec : Codec<RiffChunkHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RiffChunkHeader {
    val fourCCRaw = buffer.readInt()
    val fourCC = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw)).toUInt()
    val chunkSizeRaw = buffer.readInt()
    val chunkSize = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) chunkSizeRaw else swapBytes(chunkSizeRaw)).toUInt()
    return RiffChunkHeader(fourCC = fourCC, chunkSize = chunkSize)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RiffChunkHeader,
    context: EncodeContext,
  ) {
    val fourCCRaw = value.fourCC.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) fourCCRaw else swapBytes(fourCCRaw))
    val chunkSizeRaw = value.chunkSize.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) chunkSizeRaw else swapBytes(chunkSizeRaw))
  }

  override fun wireSize(`value`: RiffChunkHeader, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun sizeHint(`value`: RiffChunkHeader, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
