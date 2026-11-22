package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeaderCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public class Slice14cGenericFramedDispatchCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : FrameDetector {
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

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Companion.frameSize(stream, baseOffset)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public companion object : FrameDetector {
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

    override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

    @Deprecated(
      message = "Call frameSize, which does not box. Removed in the next major release.",
      replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
    )
    @Suppress("DEPRECATION")
    override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
  }
}
