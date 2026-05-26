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

public object RepeatedBlocksCodec : Codec<RepeatedBlocks> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RepeatedBlocks {
    val streamIdRaw = buffer.readShort()
    val streamId = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) streamIdRaw else swapBytes(streamIdRaw)).toUShort()
    val blocks = mutableListOf<RepeatedBlock>()
    while (buffer.position() < buffer.limit()) {
      blocks += RepeatedBlockCodec.decode(buffer, context)
    }
    return RepeatedBlocks(streamId = streamId, blocks = blocks)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RepeatedBlocks,
    context: EncodeContext,
  ) {
    val streamIdRaw = value.streamId.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) streamIdRaw else swapBytes(streamIdRaw))
    for (__elem in value.blocks) {
      RepeatedBlockCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: RepeatedBlocks, context: EncodeContext): WireSize = WireSize.Exact(2 + value.blocks.sumOf { (RepeatedBlockCodec.wireSize(it, context) as WireSize.Exact).bytes })

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
