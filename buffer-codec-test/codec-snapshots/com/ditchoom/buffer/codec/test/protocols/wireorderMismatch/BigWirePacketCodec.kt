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

public object BigWirePacketCodec : Codec<BigWirePacket> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BigWirePacket {
    val bool = buffer.readByte() != 0.toByte()
    val __batch1Raw = buffer.readInt()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val byte = (__batch1 ushr 24 and 0xFF).toByte()
    val ubyte = (__batch1 ushr 16 and 0xFF).toUByte()
    val short = (__batch1 and 0xFFFF).toShort()
    val ushortRaw = buffer.readShort()
    val ushort = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ushortRaw else swapBytes(ushortRaw)).toUShort()
    val __batch2Raw = buffer.readLong()
    val __batch2 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2Raw else swapBytes(__batch2Raw)
    val int = (__batch2 ushr 32 and 0xFFFFFFFFL).toInt()
    val uint = (__batch2 and 0xFFFFFFFFL).toUInt()
    val longRaw = buffer.readLong()
    val long = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) longRaw else swapBytes(longRaw)
    val ulongRaw = buffer.readLong()
    val ulong = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ulongRaw else swapBytes(ulongRaw)).toULong()
    val floatRaw = buffer.readInt()
    val float = Float.fromBits(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = buffer.readLong()
    val double = Double.fromBits(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) doubleRaw else swapBytes(doubleRaw))
    return BigWirePacket(bool = bool, byte = byte, ubyte = ubyte, short = short, ushort = ushort, int = int, uint = uint, long = long, ulong = ulong, float = float, double = double)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BigWirePacket,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.bool) 1.toByte() else 0.toByte())
    val __batch3 = ((value.byte.toInt() and 0xFF) shl 24) or ((value.ubyte.toInt() and 0xFF) shl 16) or (value.short.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch3 else swapBytes(__batch3))
    val ushortRaw = value.ushort.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ushortRaw else swapBytes(ushortRaw))
    val __batch4 = ((value.int.toLong() and 0xFFFFFFFFL) shl 32) or (value.uint.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch4 else swapBytes(__batch4))
    val longRaw = value.long
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) longRaw else swapBytes(longRaw))
    val ulongRaw = value.ulong.toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) ulongRaw else swapBytes(ulongRaw))
    val floatRaw = value.float.toRawBits()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) floatRaw else swapBytes(floatRaw))
    val doubleRaw = value.double.toRawBits()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) doubleRaw else swapBytes(doubleRaw))
  }

  override fun wireBytes(`value`: BigWirePacket, context: EncodeContext): WireByteCount = WireByteCount.exact(43L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: BigWirePacket, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: BigWirePacket, context: EncodeContext): Int = 43

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 43L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
