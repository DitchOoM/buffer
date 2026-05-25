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

public object RepeatedBlocksCodec : Codec<RepeatedBlocks> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RepeatedBlocks {
    val streamIdB0 = buffer.readUByte().toUInt()
    val streamIdB1 = buffer.readUByte().toUInt()
    val streamId = ((streamIdB0 shl 8) or streamIdB1).toUShort()
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
    buffer.writeUByte(((value.streamId.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.streamId.toUInt() and 0xFFu).toUByte())
    for (__elem in value.blocks) {
      RepeatedBlockCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: RepeatedBlocks, context: EncodeContext): WireSize = WireSize.Exact(2 + value.blocks.sumOf { (RepeatedBlockCodec.wireSize(it, context) as WireSize.Exact).bytes })

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
