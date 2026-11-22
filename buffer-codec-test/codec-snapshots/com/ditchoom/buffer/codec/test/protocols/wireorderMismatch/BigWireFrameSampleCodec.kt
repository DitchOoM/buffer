package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object BigWireFrameSampleCodec : Codec<BigWireFrame.Sample> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWireFrame.Sample {
    val shortRaw = buffer.readShort()
    val short = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) shortRaw else swapBytes(shortRaw)
    val intRaw = buffer.readInt()
    val int = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) intRaw else swapBytes(intRaw)
    val longRaw = buffer.readLong()
    val long = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) longRaw else swapBytes(longRaw)
    val floatRaw = buffer.readInt()
    val float = Float.fromBits(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = buffer.readLong()
    val double = Double.fromBits(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) doubleRaw else swapBytes(doubleRaw))
    return BigWireFrame.Sample(short = short, int = int, long = long, float = float, double = double)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWireFrame.Sample,
    context: EncodeContext,
  ) {
    val shortRaw = value.short
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) shortRaw else swapBytes(shortRaw))
    val intRaw = value.int
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) intRaw else swapBytes(intRaw))
    val longRaw = value.long
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) longRaw else swapBytes(longRaw))
    val floatRaw = value.float.toRawBits()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = value.double.toRawBits()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) doubleRaw else swapBytes(doubleRaw))
  }

  override fun wireBytes(`value`: BigWireFrame.Sample, context: EncodeContext): WireByteCount = WireByteCount.exact(26L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BigWireFrame.Sample, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BigWireFrame.Sample, context: EncodeContext): Int = 26

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 26L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
