package com.ditchoom.buffer.codec.test.protocols.slice14c

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

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

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 2) return FramePeek.needsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return FramePeek.needsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FramePeek.needsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
