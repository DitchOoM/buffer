package com.ditchoom.buffer.codec.test.protocols.slice14c

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object Slice14cFramedDispatchBCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cFramedDispatch.B {
    val header = Slice14cTinyHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "B.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val messagePrefixB0 = buffer.readUByte().toUInt()
      val messagePrefixB1 = buffer.readUByte().toUInt()
      val messagePrefix = ((messagePrefixB0 shl 8) or messagePrefixB1)
      if (messagePrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = messagePrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val messageLength = messagePrefix.toInt()
      val message = buffer.readText(messageLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "B.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Slice14cFramedDispatch.B(header = header, message = message)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Slice14cFramedDispatch.B,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
    headerWireWidth = 1,
    writeHeader = { buffer ->
      buffer.writeUByte(value.header.raw)
    },
  ) { buffer ->
    val messageSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val messageBodyStart = buffer.position()
    buffer.writeText(value.message, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val messageEndPosition = buffer.position()
    val messageByteCount = messageEndPosition - messageBodyStart
    if (messageByteCount > 65_535) {
      throw EncodeException(fieldPath = "B.message", reason = """UTF-8 byte length ${messageByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(messageSizePosition)
    val messagePrefix = messageByteCount.toUInt()
    buffer.writeUByte(((messagePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((messagePrefix and 0xFFu).toUByte())
    buffer.position(messageEndPosition)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
