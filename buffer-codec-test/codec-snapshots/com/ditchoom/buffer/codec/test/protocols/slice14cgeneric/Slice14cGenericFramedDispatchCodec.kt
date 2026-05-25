package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeaderCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public class Slice14cGenericFramedDispatchCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) {
  private val withPayloadCodec: Slice14cGenericFramedDispatchWithPayloadCodec<P> =
      Slice14cGenericFramedDispatchWithPayloadCodec(payloadCodec)

  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cGenericFramedDispatch<P> {
    val discriminatorPosition = buffer.position()
    val __discriminator = Slice14cTinyHeaderCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.packetType
    return when (__dispatchValue) {
      1 -> Slice14cGenericFramedDispatchHeaderedCodec.decode(buffer, context)
      2 -> withPayloadCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Slice14cGenericFramedDispatch.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2}", actual = """${__dispatchValue}""")
      }
    }
  }

  public fun encode(
    `value`: Slice14cGenericFramedDispatch<P>,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is Slice14cGenericFramedDispatch.Headered -> Slice14cGenericFramedDispatchHeaderedCodec.encode(value, context, factory)
      is Slice14cGenericFramedDispatch.WithPayload<*> -> withPayloadCodec.encode(value as Slice14cGenericFramedDispatch.WithPayload<P>, context, factory)
    }
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

  public companion object {
    public fun <P : Payload> decodeAggregating(
      buffer: ReadBuffer,
      context: DecodeContext,
      onWithPayload: (Slice14cGenericFramedDispatchWithPayloadCodec.Partial<P>) -> Slice14cGenericFramedDispatch.WithPayload<P> = { _ -> throw DecodeException(fieldPath = "Slice14cGenericFramedDispatch.WithPayload.handler", bufferPosition = -1, expected = "consumer-supplied WithPayload handler", actual = "no handler supplied") },
    ): Slice14cGenericFramedDispatch<P> {
      val discriminatorPosition = buffer.position()
      val __discriminator = Slice14cTinyHeaderCodec.decode(buffer, context)
      buffer.position(discriminatorPosition)
      val __dispatchValue = __discriminator.packetType
      return when (__dispatchValue) {
        1 -> Slice14cGenericFramedDispatchHeaderedCodec.decode(buffer, context)
        2 -> onWithPayload(Slice14cGenericFramedDispatchWithPayloadCodec.partial<P>(buffer, context))
        else -> {
          throw DecodeException(fieldPath = "Slice14cGenericFramedDispatch.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2}", actual = """${__dispatchValue}""")
        }
      }
    }
  }
}
