package com.ditchoom.buffer.codec.test.protocols.slice7c

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RepeatedBlockCodec : Codec<RepeatedBlock> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RepeatedBlock {
    val blockIdB0 = buffer.readUByte().toUInt()
    val blockIdB1 = buffer.readUByte().toUInt()
    val blockId = ((blockIdB0 shl 8) or blockIdB1).toUShort()
    val blockKind = buffer.readUByte()
    return RepeatedBlock(blockId = blockId, blockKind = blockKind)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RepeatedBlock,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.blockId.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.blockId.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(value.blockKind)
  }

  override fun wireSize(`value`: RepeatedBlock, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
