package com.ditchoom.buffer.codec.test.protocols.slice14b

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object Slice14bFramedFrameFixedCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14bFramedFrameFixed {
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Slice14bFramedFrameFixed.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val payload = buffer.readUByte()
      val tail = buffer.readUShort()
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Slice14bFramedFrameFixed.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Slice14bFramedFrameFixed(payload = payload, tail = tail)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Slice14bFramedFrameFixed,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
  ) { buffer ->
    buffer.writeUByte(value.payload)
    buffer.writeUShort(value.tail)
  }

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 1) return FramePeek.needsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 0, 5) ?: return FramePeek.needsMoreData
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
      val __total = declaredFrameBytes(0 + __framingPrefixWidth, __framingLength.toLong())
      return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
