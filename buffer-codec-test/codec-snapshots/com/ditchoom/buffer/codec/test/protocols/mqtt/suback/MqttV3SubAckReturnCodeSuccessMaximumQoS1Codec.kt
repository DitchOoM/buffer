package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV3SubAckReturnCodeSuccessMaximumQoS1Codec : Codec<MqttV3SubAckReturnCode.SuccessMaximumQoS1> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV3SubAckReturnCode.SuccessMaximumQoS1 {
    buffer.readUByte()
    return MqttV3SubAckReturnCode.SuccessMaximumQoS1
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV3SubAckReturnCode.SuccessMaximumQoS1,
    context: EncodeContext,
  ) {
    buffer.writeUByte(0x1.toUByte())
  }

  override fun wireBytes(`value`: MqttV3SubAckReturnCode.SuccessMaximumQoS1, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: MqttV3SubAckReturnCode.SuccessMaximumQoS1, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
