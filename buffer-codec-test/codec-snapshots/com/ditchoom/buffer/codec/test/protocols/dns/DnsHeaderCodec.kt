package com.ditchoom.buffer.codec.test.protocols.dns

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

public object DnsHeaderCodec : Codec<DnsHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DnsHeader {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val id = (__batch1 ushr 48 and 0xFFFFL).toUShort()
    val flags = (__batch1 ushr 32 and 0xFFFFL).toUShort()
    val qdCount = (__batch1 ushr 16 and 0xFFFFL).toUShort()
    val anCount = (__batch1 and 0xFFFFL).toUShort()
    val __batch2Raw = buffer.readInt()
    val __batch2 = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch2Raw else swapBytes(__batch2Raw)
    val nsCount = (__batch2 ushr 16 and 0xFFFF).toUShort()
    val arCount = (__batch2 and 0xFFFF).toUShort()
    return DnsHeader(id = id, flags = flags, qdCount = qdCount, anCount = anCount, nsCount = nsCount, arCount = arCount)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DnsHeader,
    context: EncodeContext,
  ) {
    val __batch3 = ((value.id.toLong() and 0xFFFFL) shl 48) or ((value.flags.toLong() and 0xFFFFL) shl 32) or ((value.qdCount.toLong() and 0xFFFFL) shl 16) or (value.anCount.toLong() and 0xFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch3 else swapBytes(__batch3))
    val __batch4 = ((value.nsCount.toInt() and 0xFFFF) shl 16) or (value.arCount.toInt() and 0xFFFF)
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch4 else swapBytes(__batch4))
  }

  override fun wireBytes(`value`: DnsHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(12L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: DnsHeader, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: DnsHeader, context: EncodeContext): Int = 12

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 12L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
