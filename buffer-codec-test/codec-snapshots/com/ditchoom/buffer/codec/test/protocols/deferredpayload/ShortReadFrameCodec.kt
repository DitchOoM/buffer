package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.UShort

public object ShortReadFrameCodec : Codec<ShortReadFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ShortReadFrame {
    val payloadLength = buffer.readUShort()
    val payloadBytes = payloadLength.toInt()
    val payloadOuterLimit = buffer.limit()
    val payloadEnd = buffer.position() + payloadBytes
    buffer.setLimit(payloadEnd)
    val payload = try {
      ShortReadPayloadCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(payloadOuterLimit)
    }
    if (buffer.position() != payloadEnd) {
      throw DecodeException(
            fieldPath = "ShortReadFrame.payload",
            bufferPosition = buffer.position(),
            expected = "the payload codec to consume all " + payloadBytes + " bytes",
            actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
          )
    }
    return ShortReadFrame(payloadLength = payloadLength, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ShortReadFrame,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.payloadLength)
    ShortReadPayloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: ShortReadFrame, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: ShortReadFrame, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    val payloadBytes = payloadLength.toInt()
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val payloadLength = buffer.readUShort()
    val __payloadStart = buffer.position()
    val __payloadEnd = __payloadStart + payloadLength.toInt()
    buffer.position(__payloadEnd)
    return Partial(payloadLength = payloadLength, payloadStart = __payloadStart, payloadEnd = __payloadEnd, buffer = buffer, context = context)
  }

  public class Partial internal constructor(
    public val payloadLength: UShort,
    private val payloadStart: Int,
    private val payloadEnd: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(): ShortReadFrame {
      buffer.position(payloadStart)
      val __payloadSavedLimit = buffer.limit()
      buffer.setLimit(payloadEnd)
      return try {
        val payload = ShortReadPayloadCodec.decode(buffer, context)
        if (buffer.position() != payloadEnd) {
          throw DecodeException(
                fieldPath = "ShortReadFrame.payload",
                bufferPosition = buffer.position(),
                expected = "the payload codec to consume all " + (payloadEnd - payloadStart) + " bytes",
                actual = (payloadEnd - buffer.position()).toString() + " bytes left unread",
              )
        }
        ShortReadFrame(payloadLength = payloadLength, payload = payload)
      } finally {
        buffer.setLimit(__payloadSavedLimit)
      }
    }
  }
}
