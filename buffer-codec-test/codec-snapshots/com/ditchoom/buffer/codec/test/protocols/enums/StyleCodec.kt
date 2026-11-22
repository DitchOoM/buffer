package com.ditchoom.buffer.codec.test.protocols.enums

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: Style, context: EncodeContext): WireByteCount {
    val __colorSize = UnsignedVarIntCodec.encodedLength(value.color.ordinal.toUInt()).toLong()
    val __prioritySize = UnsignedVarIntCodec.encodedLength(value.priority.ordinal.toUInt()).toLong()
    return WireByteCount.exact(1L + __colorSize + __prioritySize)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Style, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Style, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
