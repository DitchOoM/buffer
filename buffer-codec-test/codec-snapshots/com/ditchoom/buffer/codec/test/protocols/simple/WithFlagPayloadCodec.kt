package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String

public object WithFlagPayloadCodec : Codec<WithFlagPayload> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WithFlagPayload {
    val flags = SmallFlags(buffer.readUByte())
    val payload: String? = if (flags.want) {
      val payloadPrefixB0 = buffer.readUByte().toUInt()
      val payloadPrefixB1 = buffer.readUByte().toUInt()
      val payloadPrefix = ((payloadPrefixB0 shl 8) or payloadPrefixB1)
      if (payloadPrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "WithFlagPayload.payload", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = payloadPrefix.toString())
      }
      val payloadLength = payloadPrefix.toInt()
      buffer.readString(payloadLength, Charset.UTF8)
    } else {
      null
    }
    return WithFlagPayload(flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WithFlagPayload,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.flags.raw)
    if (value.flags.want) {
      val payloadValue = value.payload ?: throw EncodeException(fieldPath = "WithFlagPayload.payload", reason = "@When(\"flags.want\") predicate is true but field is null")
      val payloadSizePosition = buffer.position()
      repeat(2) { buffer.writeUByte(0u) }
      val payloadBodyStart = buffer.position()
      buffer.writeString(payloadValue, Charset.UTF8)
      val payloadEndPosition = buffer.position()
      val payloadByteCount = payloadEndPosition - payloadBodyStart
      if (payloadByteCount > 65_535) {
        throw EncodeException(fieldPath = "WithFlagPayload.payload", reason = """UTF-8 byte length ${payloadByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(payloadSizePosition)
      val payloadPrefix = payloadByteCount.toUInt()
      buffer.writeUByte(((payloadPrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((payloadPrefix and 0xFFu).toUByte())
      buffer.position(payloadEndPosition)
    }
  }

  override fun wireSize(`value`: WithFlagPayload, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val flagsRaw = stream.peekByte(baseOffset + __offset).toUByte()
    val flags = SmallFlags(flagsRaw)
    __offset += 1
    if (flags.want) {
      if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
      val payloadPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
      val payloadPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
      val payloadPrefix = ((payloadPrefixB0 shl 8) or payloadPrefixB1).toUInt()
      if (payloadPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
        throw DecodeException(fieldPath = "WithFlagPayload.payload", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + payloadPrefix.toInt()}""")
      }
      __offset += 2 + payloadPrefix.toInt()
    }
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
