package com.ditchoom.buffer.codec.test.protocols.batch

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

public object LittleHeaderCodec : Codec<LittleHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LittleHeader {
    val __batch1Raw = buffer.readLong()
    val __batch1 = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch1Raw else swapBytes(__batch1Raw)
    val type = (__batch1 and 0xFFL).toUByte()
    val version = (__batch1 ushr 8 and 0xFFL).toUByte()
    val flags = (__batch1 ushr 16 and 0xFFFFL).toUShort()
    val length = (__batch1 ushr 32 and 0xFFFFFFFFL).toUInt()
    return LittleHeader(type = type, version = version, flags = flags, length = length)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LittleHeader,
    context: EncodeContext,
  ) {
    val __batch2 = ((value.type.toLong() and 0xFFL) or ((value.version.toLong() and 0xFFL) shl 8) or ((value.flags.toLong() and 0xFFFFL) shl 16) or ((value.length.toLong() and 0xFFFFFFFFL) shl 32)).toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) __batch2 else swapBytes(__batch2))
  }

  override fun wireSize(`value`: LittleHeader, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
