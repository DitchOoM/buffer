package com.ditchoom.buffer.codec.test.protocols.dns

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

  override fun wireSize(`value`: DnsHeader, context: EncodeContext): WireSize = WireSize.Exact(12)

  override fun sizeHint(`value`: DnsHeader, context: EncodeContext): Int = 12

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 12) PeekResult.Complete(12) else PeekResult.NeedsMoreData
}
