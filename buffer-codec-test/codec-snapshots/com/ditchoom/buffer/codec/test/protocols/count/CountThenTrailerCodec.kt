package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object CountThenTrailerCodec : Codec<CountThenTrailer> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountThenTrailer {
    val __pointsCount = declaredFrameBytes(0, UnsignedVarIntCodec.decode(buffer, context).toLong())
    val points = ArrayList<CountPoint>(__pointsCount.coerceIn(0, 1_024))
    repeat(__pointsCount) {
      points += CountPointCodec.decode(buffer, context)
    }
    val trailerRaw = buffer.readInt()
    val trailer = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) trailerRaw else swapBytes(trailerRaw)).toUInt()
    return CountThenTrailer(points = points, trailer = trailer)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountThenTrailer,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.points.size.toUInt(), context)
    for (__elem in value.points) {
      CountPointCodec.encode(buffer, __elem, context)
    }
    val trailerRaw = value.trailer.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) trailerRaw else swapBytes(trailerRaw))
  }

  override fun wireBytes(`value`: CountThenTrailer, context: EncodeContext): WireByteCount {
    val __pointsCountSize = UnsignedVarIntCodec.encodedLength(value.points.size.toUInt()).toLong()
    var __pointsElementsSize = 0L
    val __pointsElementsSizeList = value.points
    for (__i in 0 until __pointsElementsSizeList.size) {
      __pointsElementsSize += CountPointCodec.wireBytes(__pointsElementsSizeList[__i], context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    val __pointsSize = __pointsCountSize + __pointsElementsSize
    return WireByteCount.exact(4L + __pointsSize)
  }

  override fun sizeHint(`value`: CountThenTrailer, context: EncodeContext): Int {
    var __hint = 5
    for (__elem in value.points) {
      __hint += CountPointCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
