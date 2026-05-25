package com.ditchoom.buffer.codec.test.protocols.slice14b

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object Slice14bFramedFrameVariableCodec {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14bFramedFrameVariable {
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val messagePrefixB0 = buffer.readUByte().toUInt()
      val messagePrefixB1 = buffer.readUByte().toUInt()
      val messagePrefix = ((messagePrefixB0 shl 8) or messagePrefixB1)
      if (messagePrefix > Int.MAX_VALUE.toUInt()) {
        throw DecodeException(fieldPath = "Slice14bFramedFrameVariable.message", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = messagePrefix.toString())
      }
      val messageLength = messagePrefix.toInt()
      val message = buffer.readString(messageLength, Charset.UTF8)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Slice14bFramedFrameVariable.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Slice14bFramedFrameVariable(message = message)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Slice14bFramedFrameVariable,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
  ) { buffer ->
    val messageSizePosition = buffer.position()
    buffer.position(messageSizePosition + 2)
    val messageBodyStart = buffer.position()
    buffer.writeString(value.message, Charset.UTF8)
    val messageEndPosition = buffer.position()
    val messageByteCount = messageEndPosition - messageBodyStart
    if (messageByteCount > 65_535) {
      throw EncodeException(fieldPath = "Slice14bFramedFrameVariable.message", reason = """UTF-8 byte length ${messageByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(messageSizePosition)
    val messagePrefix = messageByteCount.toUInt()
    buffer.writeUByte(((messagePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((messagePrefix and 0xFFu).toUByte())
    buffer.position(messageEndPosition)
  }

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 0, 5) ?: return PeekResult.NeedsMoreData
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
      val __total = 0 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
