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
    val __batch5Raw = buffer.readInt()
    val __batch5 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch5Raw else swapBytes(__batch5Raw)
    val byte = (__batch5 and 0xFF).toByte()
    val ubyte = (__batch5 ushr 8 and 0xFF).toUByte()
    val short = (__batch5 ushr 16 and 0xFFFF).toShort()
    val ushortRaw = buffer.readShort()
    val ushort = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) ushortRaw else swapBytes(ushortRaw)).toUShort()
    val __batch6Raw = buffer.readLong()
    val __batch6 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch6Raw else swapBytes(__batch6Raw)
    val int = (__batch6 and 0xFFFFFFFFL).toInt()
    val uint = (__batch6 ushr 32 and 0xFFFFFFFFL).toUInt()
    val longRaw = buffer.readLong()
    val long = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) longRaw else swapBytes(longRaw)
    val ulongRaw = buffer.readLong()
    val ulong = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) ulongRaw else swapBytes(ulongRaw)).toULong()
    val floatRaw = buffer.readInt()
    val float = Float.fromBits(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = buffer.readLong()
    val double = Double.fromBits(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) doubleRaw else swapBytes(doubleRaw))
    return LittleWirePacket(bool = bool, byte = byte, ubyte = ubyte, short = short, ushort = ushort, int = int, uint = uint, long = long, ulong = ulong, float = float, double = double)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LittleWirePacket,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.bool) 1.toByte() else 0.toByte())
    val __batch7 = ((value.byte.toInt() and 0xFF) or ((value.ubyte.toInt() and 0xFF) shl 8) or ((value.short.toInt() and 0xFFFF) shl 16)).toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch7 else swapBytes(__batch7))
    val ushortRaw = value.ushort.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) ushortRaw else swapBytes(ushortRaw))
    val __batch8 = ((value.int.toLong() and 0xFFFFFFFFL) or ((value.uint.toLong() and 0xFFFFFFFFL) shl 32)).toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch8 else swapBytes(__batch8))
    val longRaw = value.long
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) longRaw else swapBytes(longRaw))
    val ulongRaw = value.ulong.toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) ulongRaw else swapBytes(ulongRaw))
    val floatRaw = value.float.toRawBits()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = value.double.toRawBits()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) doubleRaw else swapBytes(doubleRaw))
  }

  override fun wireSize(`value`: LittleWirePacket, context: EncodeContext): WireSize = WireSize.Exact(43)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 43) PeekResult.Complete(43) else PeekResult.NeedsMoreData
}
