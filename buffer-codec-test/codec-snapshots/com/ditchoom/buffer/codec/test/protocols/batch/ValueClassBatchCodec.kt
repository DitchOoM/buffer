package com.ditchoom.buffer.codec.test.protocols.batch

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: ValueClassBatch, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: ValueClassBatch, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: ValueClassBatch, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(2L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
