package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeader
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.String
import kotlin.Throwable

public class Slice14cGenericFramedDispatchWithPayloadCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cGenericFramedDispatch.WithPayload<P> {
    val header = Slice14cTinyHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "WithPayload.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readString(topicLength, Charset.UTF8)
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
    buffer.position(topicSizePosition + 2)
    val topicBodyStart = buffer.position()
    buffer.writeString(value.topic, Charset.UTF8)
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

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

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

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val header = Slice14cTinyHeader(buffer.readUByte())
      val __framingOuterLimit = buffer.limit()
      val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
      MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
      val topicPrefixB0 = buffer.readUByte().toUInt()
      val topicPrefixB1 = buffer.readUByte().toUInt()
      val topicPrefix = ((topicPrefixB0 shl 8) or topicPrefixB1)
      if (topicPrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "WithPayload.topic", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = topicPrefix.toString())
      }
      val topicLength = topicPrefix.toInt()
      val topic = buffer.readString(topicLength, Charset.UTF8)
      return Partial<P>(header = header, topic = topic, outerLimit = __framingOuterLimit, buffer = buffer, context = context)
    }
  }
}
