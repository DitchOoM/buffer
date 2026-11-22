package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeader
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.String
import kotlin.Suppress

public class Slice14cGenericFramedDispatchWithPayloadCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cGenericFramedDispatch.WithPayload<P> {
    val header = Slice14cTinyHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "WithPayload.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readText(topicLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      val payload = payloadCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "WithPayload.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Slice14cGenericFramedDispatch.WithPayload<P>(header = header, topic = topic, payload = payload)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Slice14cGenericFramedDispatch.WithPayload<P>,
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
    val topicSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val topicBodyStart = buffer.position()
    buffer.writeText(value.topic, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val topicEndPosition = buffer.position()
    val topicByteCount = topicEndPosition - topicBodyStart
    if (topicByteCount > 65_535) {
      throw EncodeException(fieldPath = "WithPayload.topic", reason = """UTF-8 byte length ${topicByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(topicSizePosition)
    val topicPrefix = topicByteCount.toUInt()
    buffer.writeUByte(((topicPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((topicPrefix and 0xFFu).toUByte())
    buffer.position(topicEndPosition)
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Companion.frameSize(stream, baseOffset)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public class Partial<P : Payload> internal constructor(
    public val `header`: Slice14cTinyHeader,
    public val topic: String,
    private val outerLimit: Int,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): Slice14cGenericFramedDispatch.WithPayload<P> = try {
      val payload = payloadCodec.decode(buffer, context)
      Slice14cGenericFramedDispatch.WithPayload<P>(header = header, topic = topic, payload = payload)
    } finally {
      buffer.setLimit(outerLimit)
    }
  }

  public companion object : FrameDetector {
    override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

    @Deprecated(
      message = "Call frameSize, which does not box. Removed in the next major release.",
      replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
    )
    @Suppress("DEPRECATION")
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val header = Slice14cTinyHeader(buffer.readUByte())
      val __framingOuterLimit = buffer.limit()
      val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
      if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
        throw DecodeException(
              fieldPath = "WithPayload.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "a fully-buffered " + __framingLength + "-byte framed body",
              actual = buffer.remaining().toString() + " bytes available",
            )
      }
      MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = topicPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readText(topicLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      return Partial<P>(header = header, topic = topic, outerLimit = __framingOuterLimit, buffer = buffer, context = context)
    }
  }
}
