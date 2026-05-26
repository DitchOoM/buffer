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

public object LittleWirePacketCodec : Codec<LittleWirePacket> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LittleWirePacket {
    val bool = buffer.readByte() != 0.toByte()
    val __batch7Raw = buffer.readInt()
    val __batch7 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch7Raw else swapBytes(__batch7Raw)
    val byte = (__batch7 and 0xFF).toByte()
    val ubyte = (__batch7 ushr 8 and 0xFF).toUByte()
    val short = (__batch7 ushr 16 and 0xFFFF).toShort()
    val ushortB0 = buffer.readUByte().toUInt()
    val ushortB1 = buffer.readUByte().toUInt()
    val ushort = (ushortB0 or (ushortB1 shl 8)).toUShort()
    val __batch8Raw = buffer.readLong()
    val __batch8 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch8Raw else swapBytes(__batch8Raw)
    val int = (__batch8 and 0xFFFFFFFFL).toInt()
    val uint = (__batch8 ushr 32 and 0xFFFFFFFFL).toUInt()
    val longB0 = buffer.readUByte().toULong()
    val longB1 = buffer.readUByte().toULong()
    val longB2 = buffer.readUByte().toULong()
    val longB3 = buffer.readUByte().toULong()
    val longB4 = buffer.readUByte().toULong()
    val longB5 = buffer.readUByte().toULong()
    val longB6 = buffer.readUByte().toULong()
    val longB7 = buffer.readUByte().toULong()
    val long = (longB0 or (longB1 shl 8) or (longB2 shl 16) or (longB3 shl 24) or (longB4 shl 32) or (longB5 shl 40) or (longB6 shl 48) or (longB7 shl 56)).toLong()
    val ulongB0 = buffer.readUByte().toULong()
    val ulongB1 = buffer.readUByte().toULong()
    val ulongB2 = buffer.readUByte().toULong()
    val ulongB3 = buffer.readUByte().toULong()
    val ulongB4 = buffer.readUByte().toULong()
    val ulongB5 = buffer.readUByte().toULong()
    val ulongB6 = buffer.readUByte().toULong()
    val ulongB7 = buffer.readUByte().toULong()
    val ulong = (ulongB0 or (ulongB1 shl 8) or (ulongB2 shl 16) or (ulongB3 shl 24) or (ulongB4 shl 32) or (ulongB5 shl 40) or (ulongB6 shl 48) or (ulongB7 shl 56))
    val floatB0 = buffer.readUByte().toUInt()
    val floatB1 = buffer.readUByte().toUInt()
    val floatB2 = buffer.readUByte().toUInt()
    val floatB3 = buffer.readUByte().toUInt()
    val float = Float.fromBits((floatB0 or (floatB1 shl 8) or (floatB2 shl 16) or (floatB3 shl 24)).toInt())
    val doubleB0 = buffer.readUByte().toULong()
    val doubleB1 = buffer.readUByte().toULong()
    val doubleB2 = buffer.readUByte().toULong()
    val doubleB3 = buffer.readUByte().toULong()
    val doubleB4 = buffer.readUByte().toULong()
    val doubleB5 = buffer.readUByte().toULong()
    val doubleB6 = buffer.readUByte().toULong()
    val doubleB7 = buffer.readUByte().toULong()
    val double = Double.fromBits((doubleB0 or (doubleB1 shl 8) or (doubleB2 shl 16) or (doubleB3 shl 24) or (doubleB4 shl 32) or (doubleB5 shl 40) or (doubleB6 shl 48) or (doubleB7 shl 56)).toLong())
    return LittleWirePacket(bool = bool, byte = byte, ubyte = ubyte, short = short, ushort = ushort, int = int, uint = uint, long = long, ulong = ulong, float = float, double = double)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LittleWirePacket,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.bool) 1.toByte() else 0.toByte())
    val __batch9 = ((value.byte.toInt() and 0xFF) or ((value.ubyte.toInt() and 0xFF) shl 8) or ((value.short.toInt() and 0xFFFF) shl 16)).toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch9 else swapBytes(__batch9))
    buffer.writeUByte((value.ushort.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.ushort.toUInt() shr 8) and 0xFFu).toUByte())
    val __batch10 = ((value.int.toLong() and 0xFFFFFFFFL) or ((value.uint.toLong() and 0xFFFFFFFFL) shl 32)).toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch10 else swapBytes(__batch10))
    buffer.writeUByte((value.long.toULong() and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.long.toULong() shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte((value.ulong and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.ulong shr 56) and 0xFFuL).toUByte())
    buffer.writeUByte((value.float.toRawBits().toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.float.toRawBits().toUInt() shr 24) and 0xFFu).toUByte())
    buffer.writeUByte((value.double.toRawBits().toULong() and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 8) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 16) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 24) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 32) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 40) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 48) and 0xFFuL).toUByte())
    buffer.writeUByte(((value.double.toRawBits().toULong() shr 56) and 0xFFuL).toUByte())
  }

  override fun wireSize(`value`: LittleWirePacket, context: EncodeContext): WireSize = WireSize.Exact(43)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 43) PeekResult.Complete(43) else PeekResult.NeedsMoreData
}
