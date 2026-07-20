package com.ditchoom.buffer.codec.test.protocols.enums

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TaggedCodec : Codec<Tagged> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Tagged {
    val __levelOrdinal = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val level = Priority.entries.getOrElse(__levelOrdinal) { throw DecodeException(fieldPath = "Tagged.level", bufferPosition = buffer.position(), expected = "an ordinal in 0 until 3", actual = __levelOrdinal.toString()) }
    val id = buffer.readUByte()
    return Tagged(level = level, id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Tagged,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.level.ordinal.toUInt(), context)
    buffer.writeUByte(value.id)
  }

  override fun wireSize(`value`: Tagged, context: EncodeContext): WireSize {
    val __levelSize = (UnsignedVarIntCodec.wireSize(value.level.ordinal.toUInt(), context) as WireSize.Exact).bytes
    return WireSize.Exact(1 + __levelSize)
  }

  override fun sizeHint(`value`: Tagged, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __levelFrame = UnsignedVarIntCodec.peekFrameSize(stream, baseOffset + 0)
    if (__levelFrame !is PeekResult.Complete) {
      return __levelFrame
    }
    val __total = 0 + __levelFrame.bytes + 1
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
