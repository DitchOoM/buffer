package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

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

  override fun wireSize(`value`: BigWireFrame.Sample, context: EncodeContext): WireSize = WireSize.Exact(26)

  override fun sizeHint(`value`: BigWireFrame.Sample, context: EncodeContext): Int = 26

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 26) PeekResult.Complete(26) else PeekResult.NeedsMoreData
}
