package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
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
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: CountThenTrailer, context: EncodeContext): WireByteCount {
    val __pointsCountSize = UnsignedVarIntCodec.encodedLength(value.points.size.toUInt()).toLong()
    var __pointsElementsSize = 0L
    for (__elem in value.points) {
      __pointsElementsSize += CountPointCodec.wireBytes(__elem, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    val __pointsSize = __pointsCountSize + __pointsElementsSize
    return WireByteCount.exact(4L + __pointsSize)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountThenTrailer, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountThenTrailer, context: EncodeContext): Int {
    var __hint = 5
    for (__elem in value.points) {
      __hint += CountPointCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
