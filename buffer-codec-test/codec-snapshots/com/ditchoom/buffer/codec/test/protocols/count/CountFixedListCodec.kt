package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object CountFixedListCodec : Codec<CountFixedList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountFixedList {
    val id = buffer.readUByte()
    val __pointsCount = declaredFrameBytes(0, UnsignedVarIntCodec.decode(buffer, context).toLong())
    val points = ArrayList<CountPoint>(__pointsCount.coerceIn(0, 1_024))
    repeat(__pointsCount) {
      points += CountPointCodec.decode(buffer, context)
    }
    return CountFixedList(id = id, points = points)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountFixedList,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id)
    UnsignedVarIntCodec.encode(buffer, value.points.size.toUInt(), context)
    for (__elem in value.points) {
      CountPointCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireBytes(`value`: CountFixedList, context: EncodeContext): WireByteCount {
    val __pointsCountSize = UnsignedVarIntCodec.encodedLength(value.points.size.toUInt()).toLong()
    var __pointsElementsSize = 0L
    val __pointsElementsSizeList = value.points
    for (__i in 0 until __pointsElementsSizeList.size) {
      __pointsElementsSize += CountPointCodec.wireBytes(__pointsElementsSizeList[__i], context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    val __pointsSize = __pointsCountSize + __pointsElementsSize
    return WireByteCount.exact(1L + __pointsSize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountFixedList, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountFixedList, context: EncodeContext): Int {
    var __hint = 2
    for (__elem in value.points) {
      __hint += CountPointCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
