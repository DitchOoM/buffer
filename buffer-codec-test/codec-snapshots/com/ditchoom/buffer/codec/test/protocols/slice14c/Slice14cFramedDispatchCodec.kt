package com.ditchoom.buffer.codec.test.protocols.slice14c

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Slice14cFramedDispatchCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cFramedDispatch {
    val discriminatorPosition = buffer.position()
    val __discriminator = Slice14cTinyHeaderCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.packetType
    return when (__dispatchValue) {
      1 -> Slice14cFramedDispatchACodec.decode(buffer, context)
      2 -> Slice14cFramedDispatchBCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "Slice14cFramedDispatch.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2}", actual = """${__dispatchValue}""")
      }
    }
  }

  public fun encode(
    `value`: Slice14cFramedDispatch,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = when (value) {
    is Slice14cFramedDispatch.A -> Slice14cFramedDispatchACodec.encode(value, context, factory)
    is Slice14cFramedDispatch.B -> Slice14cFramedDispatchBCodec.encode(value, context, factory)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
}
