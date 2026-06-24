package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object CountThenTrailerCodec : Codec<CountThenTrailer> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountThenTrailer {
    val __pointsCount = UnsignedVarIntCodec.decode(buffer, context).toInt()
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

  override fun wireSize(`value`: CountThenTrailer, context: EncodeContext): WireSize {
    val __pointsCountSize = (UnsignedVarIntCodec.wireSize(value.points.size.toUInt(), context) as WireSize.Exact).bytes
    val __pointsSize = __pointsCountSize + value.points.sumOf { (CountPointCodec.wireSize(it, context) as WireSize.Exact).bytes }
    return WireSize.Exact(4 + __pointsSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
