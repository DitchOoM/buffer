package com.ditchoom.buffer.codec.test.protocols.slice7c

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: RepeatedBlocks, context: EncodeContext): WireByteCount {
    var __blocksBodySize = 0L
    val __blocksBodySizeList = value.blocks
    for (__i in 0 until __blocksBodySizeList.size) {
      __blocksBodySize += RepeatedBlockCodec.wireBytes(__blocksBodySizeList[__i], context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    return WireByteCount.exact(2L + __blocksBodySize)
  }

  override fun sizeHint(`value`: RepeatedBlocks, context: EncodeContext): Int {
    var __hint = 2
    for (__elem in value.blocks) {
      __hint += RepeatedBlockCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
