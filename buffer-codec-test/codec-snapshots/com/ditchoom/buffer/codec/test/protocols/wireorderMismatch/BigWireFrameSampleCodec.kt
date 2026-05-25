package com.ditchoom.buffer.codec.test.protocols.wireorderMismatch

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object BigWireFrameSampleCodec : Codec<BigWireFrame.Sample> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWireFrame.Sample {
    val shortB0 = buffer.readUByte().toUInt()
    val shortB1 = buffer.readUByte().toUInt()
    val short = ((shortB0 shl 8) or shortB1).toShort()
    val intB0 = buffer.readUByte().toUInt()
    val intB1 = buffer.readUByte().toUInt()
    val intB2 = buffer.readUByte().toUInt()
    val intB3 = buffer.readUByte().toUInt()
    val int = ((intB0 shl 24) or (intB1 shl 16) or (intB2 shl 8) or intB3).toInt()
    val longB0 = buffer.readUByte().toULong()
    val longB1 = buffer.readUByte().toULong()
    val longB2 = buffer.readUByte().toULong()
    val longB3 = buffer.readUByte().toULong()
    val longB4 = buffer.readUByte().toULong()
    val longB5 = buffer.readUByte().toULong()
    val longB6 = buffer.readUByte().toULong()
    val longB7 = buffer.readUByte().toULong()
    val long = ((longB0 shl 56) or (longB1 shl 48) or (longB2 shl 40) or (longB3 shl 32) or (longB4 shl 24) or (longB5 shl 16) or (longB6 shl 8) or longB7).toLong()
    val floatB0 = buffer.readUByte().toUInt()
    val floatB1 = buffer.readUByte().toUInt()
    val floatB2 = buffer.readUByte().toUInt()
    val floatB3 = buffer.readUByte().toUInt()
    val float = Float.fromBits(((floatB0 shl 24) or (floatB1 shl 16) or (floatB2 shl 8) or floatB3).toInt())
    val doubleB0 = buffer.readUByte().toULong()
    val doubleB1 = buffer.readUByte().toULong()
    val doubleB2 = buffer.readUByte().toULong()
    val doubleB3 = buffer.readUByte().toULong()
    val doubleB4 = buffer.readUByte().toULong()
    val doubleB5 = buffer.readUByte().toULong()
    val doubleB6 = buffer.readUByte().toULong()
    val doubleB7 = buffer.readUByte().toULong()
    val double = Double.fromBits(((doubleB0 shl 56) or (doubleB1 shl 48) or (doubleB2 shl 40) or (doubleB3 shl 32) or (doubleB4 shl 24) or (doubleB5 shl 16) or (doubleB6 shl 8) or doubleB7).toLong())
    return BigWireFrame.Sample(short = short, int = int, long = long, float = float, double = double)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWireFrame.Sample,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.short.toUShort().toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.short.toUShort().toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.int.toUInt() shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.int.toUInt() shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.int.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.int.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte((value.long.toULong() and 0xFFuL).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.float.toRawBits().toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte((value.double.toRawBits().toULong() and 0xFFuL).toUByte())
  }

  override fun wireSize(`value`: BigWireFrame.Sample, context: EncodeContext): WireSize = WireSize.Exact(26)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 26) PeekResult.Complete(26) else PeekResult.NeedsMoreData
}
