package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

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

  override fun wireSize(`value`: MqttV3SubAckReturnCode, context: EncodeContext): WireSize = when (value) {
    is MqttV3SubAckReturnCode.SuccessMaximumQoS0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.wireSize(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.wireSize(value, context)
    is MqttV3SubAckReturnCode.SuccessMaximumQoS2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.wireSize(value, context)
    is MqttV3SubAckReturnCode.Failure -> MqttV3SubAckReturnCodeFailureCodec.wireSize(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = MqttV3SubAckReturnCodeRaw(__discRaw)
    val __dispatchValue = __discriminator.id
    return when (__dispatchValue) {
      0 -> MqttV3SubAckReturnCodeSuccessMaximumQoS0Codec.peekFrameSize(stream, baseOffset)
      1 -> MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec.peekFrameSize(stream, baseOffset)
      2 -> MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec.peekFrameSize(stream, baseOffset)
      128 -> MqttV3SubAckReturnCodeFailureCodec.peekFrameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "MqttV3SubAckReturnCode.discriminator", bufferPosition = baseOffset, expected = "one of {0, 1, 2, 128}", actual = """${__dispatchValue}""")
      }
    }
  }
}
