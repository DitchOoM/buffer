package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
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

  override fun wireSize(`value`: ConditionalBreaksBatch, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val hasExtra = stream.peekByte(baseOffset + __offset) != 0.toByte()
    __offset += 1
    if (hasExtra) {
      if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
      __offset += 1
    }
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
