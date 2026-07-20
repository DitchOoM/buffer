package com.ditchoom.buffer.codec.test.protocols.simple

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

public object WithOptionalCodec : Codec<WithOptional> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WithOptional {
    val hasExtra = buffer.readByte() != 0.toByte()
    val extra: Int? = if (hasExtra) buffer.readInt() else null
    return WithOptional(hasExtra = hasExtra, extra = extra)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WithOptional,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.hasExtra) 1.toByte() else 0.toByte())
    if (value.hasExtra) {
      val extraValue = value.extra ?: throw EncodeException(fieldPath = "WithOptional.extra", reason = "@When(\"hasExtra\") predicate is true but field is null")
      buffer.writeInt(extraValue)
    }
  }

  override fun wireSize(`value`: WithOptional, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: WithOptional, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val hasExtra = stream.peekByte(baseOffset + __offset) != 0.toByte()
    __offset += 1
    if (hasExtra) {
      if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
      __offset += 4
    }
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
