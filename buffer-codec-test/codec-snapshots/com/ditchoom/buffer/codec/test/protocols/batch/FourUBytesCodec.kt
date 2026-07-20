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

public object FourUBytesCodec : Codec<FourUBytes> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): FourUBytes {
    val __batch1 = buffer.readInt()
    val a: kotlin.UByte
    val b: kotlin.UByte
    val c: kotlin.UByte
    val d: kotlin.UByte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      a = (__batch1 ushr 24 and 0xFF).toUByte()
      b = (__batch1 ushr 16 and 0xFF).toUByte()
      c = (__batch1 ushr 8 and 0xFF).toUByte()
      d = (__batch1 and 0xFF).toUByte()
    } else {
      a = (__batch1 and 0xFF).toUByte()
      b = (__batch1 ushr 8 and 0xFF).toUByte()
      c = (__batch1 ushr 16 and 0xFF).toUByte()
      d = (__batch1 ushr 24 and 0xFF).toUByte()
    }
    return FourUBytes(a = a, b = b, c = c, d = d)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: FourUBytes,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeInt(((value.a.toInt() and 0xFF) shl 24) or ((value.b.toInt() and 0xFF) shl 16) or ((value.c.toInt() and 0xFF) shl 8) or (value.d.toInt() and 0xFF))
    } else {
      buffer.writeInt((value.a.toInt() and 0xFF) or ((value.b.toInt() and 0xFF) shl 8) or ((value.c.toInt() and 0xFF) shl 16) or ((value.d.toInt() and 0xFF) shl 24))
    }
  }

  override fun wireSize(`value`: FourUBytes, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun sizeHint(`value`: FourUBytes, context: EncodeContext): Int = 4

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
