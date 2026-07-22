package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.UByte
import kotlin.UInt

public object WideLengthDeferredFrameCodec : Codec<WideLengthDeferredFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WideLengthDeferredFrame {
    val payloadLength = buffer.readUInt()
    val flags = buffer.readUByte()
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WideLengthDeferredFrame.payload", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = payloadLength.toString())
    }
    val payloadBytes = payloadLength.toInt()
    val payloadOuterLimit = buffer.limit()
    val payloadEnd = buffer.position() + payloadBytes
    buffer.setLimit(payloadEnd)
    val payload = try {
      TextPayloadCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(payloadOuterLimit)
    }
    if (buffer.position() != payloadEnd) {
      throw DecodeException(
            fieldPath = "WideLengthDeferredFrame.payload",
            bufferPosition = buffer.position(),
            expected = "the payload codec to consume all " + payloadBytes + " bytes",
            actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
          )
    }
    return WideLengthDeferredFrame(payloadLength = payloadLength, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WideLengthDeferredFrame,
    context: EncodeContext,
  ) {
    buffer.writeUInt(value.payloadLength)
    buffer.writeUByte(value.flags)
    TextPayloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: WideLengthDeferredFrame, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: WideLengthDeferredFrame, context: EncodeContext): Int = 5

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val payloadLengthB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 24) or (payloadLengthB1 shl 16) or (payloadLengthB2 shl 8) or payloadLengthB3).toUInt()
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WideLengthDeferredFrame.payload", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = payloadLength.toString())
    }
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw DecodeException(fieldPath = "WideLengthDeferredFrame.payload", bufferPosition = baseOffset + __offset, expected = "__offset + @LengthFrom source in 0..${'$'}{Int.MAX_VALUE}", actual = """${__offset.toLong() + payloadBytes.toLong()}""")
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val payloadLength = buffer.readUInt()
    val flags = buffer.readUByte()
    val __payloadStart = buffer.position()
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "WideLengthDeferredFrame.payload", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = payloadLength.toString())
    }
    val __payloadEnd = __payloadStart + payloadLength.toInt()
    buffer.position(__payloadEnd)
    return Partial(payloadLength = payloadLength, flags = flags, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val payloadLength: UInt,
    public val flags: UByte,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): WideLengthDeferredFrame {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = TextPayloadCodec.decode(buffer, context)
        if (buffer.position() != payloadEnd) {
          throw DecodeException(
                fieldPath = "WideLengthDeferredFrame.payload",
                bufferPosition = buffer.position(),
                expected = "the payload codec to consume all " + (payloadEnd - payloadStart) + " bytes",
                actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
              )
        }
        WideLengthDeferredFrame(payloadLength = payloadLength, flags = flags, payload = payload)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }
}
