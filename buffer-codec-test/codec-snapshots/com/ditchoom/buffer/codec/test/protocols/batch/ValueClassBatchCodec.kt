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

public object ValueClassBatchCodec : Codec<ValueClassBatch> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ValueClassBatch {
    val __batch1 = buffer.readShort().toInt() and 0xFFFF
    val header: com.ditchoom.buffer.codec.test.protocols.batch.HeaderByte
    val tail: com.ditchoom.buffer.codec.test.protocols.batch.HeaderByte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      header = HeaderByte((__batch1 ushr 8 and 0xFF).toUByte())
      tail = HeaderByte((__batch1 and 0xFF).toUByte())
    } else {
      header = HeaderByte((__batch1 and 0xFF).toUByte())
      tail = HeaderByte((__batch1 ushr 8 and 0xFF).toUByte())
    }
    return ValueClassBatch(header = header, tail = tail)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ValueClassBatch,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.header.raw.toInt() and 0xFF) shl 8) or (value.tail.raw.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.header.raw.toInt() and 0xFF) or ((value.tail.raw.toInt() and 0xFF) shl 8)).toShort())
    }
  }

  override fun wireSize(`value`: ValueClassBatch, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: ValueClassBatch, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
