package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.UByte

public object ConditionalBreaksBatchCodec : Codec<ConditionalBreaksBatch> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ConditionalBreaksBatch {
    val header = buffer.readUByte()
    val hasExtra = buffer.readByte() != 0.toByte()
    val extra: UByte? = if (hasExtra) buffer.readUByte() else null
    val trailer = buffer.readUByte()
    return ConditionalBreaksBatch(header = header, hasExtra = hasExtra, extra = extra, trailer = trailer)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ConditionalBreaksBatch,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.header)
    buffer.writeByte(if (value.hasExtra) 1.toByte() else 0.toByte())
    if (value.hasExtra) {
      val extraValue = value.extra ?: throw EncodeException(fieldPath = "ConditionalBreaksBatch.extra", reason = "@When(\"hasExtra\") predicate is true but field is null")
      buffer.writeUByte(extraValue)
    }
    buffer.writeUByte(value.trailer)
  }

  override fun wireBytes(`value`: ConditionalBreaksBatch, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: ConditionalBreaksBatch, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: ConditionalBreaksBatch, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val hasExtra = stream.peekByte(baseOffset + __offset) != 0.toByte()
    __offset += 1
    if (hasExtra) {
      if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
      __offset += 1
    }
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    return FrameSize.declared(__offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
