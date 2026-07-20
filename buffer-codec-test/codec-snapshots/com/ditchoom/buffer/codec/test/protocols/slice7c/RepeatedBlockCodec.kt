package com.ditchoom.buffer.codec.test.protocols.slice7c

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

public object RepeatedBlockCodec : Codec<RepeatedBlock> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RepeatedBlock {
    val blockIdRaw = buffer.readShort()
    val blockId = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) blockIdRaw else swapBytes(blockIdRaw)).toUShort()
    val blockKind = buffer.readUByte()
    return RepeatedBlock(blockId = blockId, blockKind = blockKind)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RepeatedBlock,
    context: EncodeContext,
  ) {
    val blockIdRaw = value.blockId.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) blockIdRaw else swapBytes(blockIdRaw))
    buffer.writeUByte(value.blockKind)
  }

  override fun wireSize(`value`: RepeatedBlock, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun sizeHint(`value`: RepeatedBlock, context: EncodeContext): Int = 3

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
