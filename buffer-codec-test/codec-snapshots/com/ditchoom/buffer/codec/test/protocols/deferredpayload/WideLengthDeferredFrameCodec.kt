package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.TextPayloadCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.UByte
import kotlin.UInt

public object WideLengthDeferredFrameCodec : Codec<WideLengthDeferredFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WideLengthDeferredFrame {
    val payloadLength = buffer.readUInt()
    val flags = buffer.readUByte()
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = payloadLength.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "WideLengthDeferredFrame.payload",
            bufferPosition = buffer.position(),
            expected = "a " + payloadBytes + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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

  override fun wireBytes(`value`: WideLengthDeferredFrame, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: WideLengthDeferredFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: WideLengthDeferredFrame, context: EncodeContext): Int = 5

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val payloadLengthB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 24) or (payloadLengthB1 shl 16) or (payloadLengthB2 shl 8) or payloadLengthB3).toUInt()
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = payloadLength.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += payloadBytes
    return FrameSize.declared(__offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public fun partial(buffer: ReadBuffer, context: DecodeContext): Partial {
    val payloadLength = buffer.readUInt()
    val flags = buffer.readUByte()
    val __payloadStart = buffer.position()
    if (payloadLength > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = payloadLength.toLong(), maxBytes = Int.MAX_VALUE)
    }
    if (payloadLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "WideLengthDeferredFrame.payload",
            bufferPosition = buffer.position(),
            expected = "a " + payloadLength.toInt() + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
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
