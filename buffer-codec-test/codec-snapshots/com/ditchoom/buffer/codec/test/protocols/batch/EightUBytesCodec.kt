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

public object EightUBytesCodec : Codec<EightUBytes> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EightUBytes {
    val __batch1 = buffer.readLong()
    val a: kotlin.UByte
    val b: kotlin.UByte
    val c: kotlin.UByte
    val d: kotlin.UByte
    val e: kotlin.UByte
    val f: kotlin.UByte
    val g: kotlin.UByte
    val h: kotlin.UByte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      a = (__batch1 ushr 56 and 0xFFL).toUByte()
      b = (__batch1 ushr 48 and 0xFFL).toUByte()
      c = (__batch1 ushr 40 and 0xFFL).toUByte()
      d = (__batch1 ushr 32 and 0xFFL).toUByte()
      e = (__batch1 ushr 24 and 0xFFL).toUByte()
      f = (__batch1 ushr 16 and 0xFFL).toUByte()
      g = (__batch1 ushr 8 and 0xFFL).toUByte()
      h = (__batch1 and 0xFFL).toUByte()
    } else {
      a = (__batch1 and 0xFFL).toUByte()
      b = (__batch1 ushr 8 and 0xFFL).toUByte()
      c = (__batch1 ushr 16 and 0xFFL).toUByte()
      d = (__batch1 ushr 24 and 0xFFL).toUByte()
      e = (__batch1 ushr 32 and 0xFFL).toUByte()
      f = (__batch1 ushr 40 and 0xFFL).toUByte()
      g = (__batch1 ushr 48 and 0xFFL).toUByte()
      h = (__batch1 ushr 56 and 0xFFL).toUByte()
    }
    return EightUBytes(a = a, b = b, c = c, d = d, e = e, f = f, g = g, h = h)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EightUBytes,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeLong((((value.a.toLong() and 0xFFL) shl 56) or ((value.b.toLong() and 0xFFL) shl 48) or ((value.c.toLong() and 0xFFL) shl 40) or ((value.d.toLong() and 0xFFL) shl 32) or ((value.e.toLong() and 0xFFL) shl 24) or ((value.f.toLong() and 0xFFL) shl 16) or ((value.g.toLong() and 0xFFL) shl 8) or (value.h.toLong() and 0xFFL)).toLong())
    } else {
      buffer.writeLong(((value.a.toLong() and 0xFFL) or ((value.b.toLong() and 0xFFL) shl 8) or ((value.c.toLong() and 0xFFL) shl 16) or ((value.d.toLong() and 0xFFL) shl 24) or ((value.e.toLong() and 0xFFL) shl 32) or ((value.f.toLong() and 0xFFL) shl 40) or ((value.g.toLong() and 0xFFL) shl 48) or ((value.h.toLong() and 0xFFL) shl 56)).toLong())
    }
  }

  override fun wireSize(`value`: EightUBytes, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
