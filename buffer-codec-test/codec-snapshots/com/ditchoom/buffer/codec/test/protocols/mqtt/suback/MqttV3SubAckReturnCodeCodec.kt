package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object MqttV3SubAckReturnCodeCodec : Codec<MqttV3SubAckReturnCode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV3SubAckReturnCode {
    val discriminatorPosition = buffer.position()
    val __discriminator = MqttV3SubAckReturnCodeRawCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.decode(buffer, context)
      1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.decode(buffer, context)
      2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.decode(buffer, context)
      128 -> MqttV3SubAckReturnCodeFailureCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "MqttV3SubAckReturnCode.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1, 2, 128}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV3SubAckReturnCode,
    context: EncodeContext,
  ) {
    when (value) {
      is MqttV3SubAckReturnCode.SuccessMaximumQoS0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.encode(buffer, value, context)
      is MqttV3SubAckReturnCode.SuccessMaximumQoS1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.encode(buffer, value, context)
      is MqttV3SubAckReturnCode.SuccessMaximumQoS2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.encode(buffer, value, context)
      is MqttV3SubAckReturnCode.Failure -> MqttV3SubAckReturnCodeFailureCodec.encode(buffer, value, context)
    }
  }

  override fun wireBytes(`value`: MqttV3SubAckReturnCode, context: EncodeContext): WireByteCount = when (value) {
    is MqttV3SubAckReturnCode.SuccessMaximumQoS0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.wireBytes(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.wireBytes(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.wireBytes(value, context)
    is MqttV3SubAckReturnCode.Failure -> MqttV3SubAckReturnCodeFailureCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: MqttV3SubAckReturnCode, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: MqttV3SubAckReturnCode, context: EncodeContext): Int = when (value) {
    is MqttV3SubAckReturnCode.SuccessMaximumQoS0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.sizeHint(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.sizeHint(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.sizeHint(value, context)
    is MqttV3SubAckReturnCode.Failure -> MqttV3SubAckReturnCodeFailureCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = MqttV3SubAckReturnCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.frameSize(stream, baseOffset)
      1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.frameSize(stream, baseOffset)
      2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.frameSize(stream, baseOffset)
      128 -> MqttV3SubAckReturnCodeFailureCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "MqttV3SubAckReturnCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 2, 128}", actual = """${__dispatchValue}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
