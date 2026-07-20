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
import kotlin.Int

public object MixedNaturalScalarsCodec : Codec<MixedNaturalScalars> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MixedNaturalScalars {
    val __batch1 = buffer.readLong()
    val flags: kotlin.UByte
    val tag: kotlin.UByte
    val length: kotlin.UShort
    val checksum: kotlin.UInt
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      flags = (__batch1 ushr 56 and 0xFFL).toUByte()
      tag = (__batch1 ushr 48 and 0xFFL).toUByte()
      length = (__batch1 ushr 32 and 0xFFFFL).toUShort()
      checksum = (__batch1 and 0xFFFFFFFFL).toUInt()
    } else {
      flags = (__batch1 and 0xFFL).toUByte()
      tag = (__batch1 ushr 8 and 0xFFL).toUByte()
      length = (__batch1 ushr 16 and 0xFFFFL).toUShort()
      checksum = (__batch1 ushr 32 and 0xFFFFFFFFL).toUInt()
    }
    return MixedNaturalScalars(flags = flags, tag = tag, length = length, checksum = checksum)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MixedNaturalScalars,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeLong(((value.flags.toLong() and 0xFFL) shl 56) or ((value.tag.toLong() and 0xFFL) shl 48) or ((value.length.toLong() and 0xFFFFL) shl 32) or (value.checksum.toLong() and 0xFFFFFFFFL))
    } else {
      buffer.writeLong((value.flags.toLong() and 0xFFL) or ((value.tag.toLong() and 0xFFL) shl 8) or ((value.length.toLong() and 0xFFFFL) shl 16) or ((value.checksum.toLong() and 0xFFFFFFFFL) shl 32))
    }
  }

  override fun wireSize(`value`: MixedNaturalScalars, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun sizeHint(`value`: MixedNaturalScalars, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
