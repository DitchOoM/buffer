package com.ditchoom.buffer.codec.test.protocols.enums

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: Tagged, context: EncodeContext): WireByteCount {
    val __levelSize = UnsignedVarIntCodec.encodedLength(value.level.ordinal.toUInt()).toLong()
    return WireByteCount.exact(1L + __levelSize)
  }

  override fun sizeHint(`value`: Tagged, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __levelWidth = UnsignedVarIntCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 1L + __levelWidth)
  }
}
