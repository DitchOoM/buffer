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

public object StyleCodec : Codec<Style> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Style {
    val __colorOrdinal = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val color = Color.entries.getOrElse(__colorOrdinal) { Color.Unknown }
    val __priorityOrdinal = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val priority = Priority.entries.getOrElse(__priorityOrdinal) { throw DecodeException(fieldPath = "Style.priority", bufferPosition = buffer.position(), expected = "an ordinal in 0 until 3", actual = __priorityOrdinal.toString()) }
    val weight = buffer.readUByte()
    return Style(color = color, priority = priority, weight = weight)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Style,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.color.ordinal.toUInt(), context)
    UnsignedVarIntCodec.encode(buffer, value.priority.ordinal.toUInt(), context)
    buffer.writeUByte(value.weight)
  }

  override fun wireSize(`value`: Style, context: EncodeContext): WireSize {
    val __colorSize = (UnsignedVarIntCodec.wireSize(value.color.ordinal.toUInt(), context) as WireSize.Exact).bytes
    val __prioritySize = (UnsignedVarIntCodec.wireSize(value.priority.ordinal.toUInt(), context) as WireSize.Exact).bytes
    return WireSize.Exact(1 + __colorSize + __prioritySize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
