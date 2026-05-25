package com.ditchoom.buffer.codec.test.protocols.mqtt.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV3SubAckReturnCodeSuccessMaximumQoS2Codec : Codec<MqttV3SubAckReturnCode.SuccessMaximumQoS2> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV3SubAckReturnCode.SuccessMaximumQoS2 {
    buffer.readUByte()
    return MqttV3SubAckReturnCode.SuccessMaximumQoS2
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV3SubAckReturnCode.SuccessMaximumQoS2,
    context: EncodeContext,
  ) {
    buffer.writeUByte(0x2.toUByte())
  }

  override fun wireSize(`value`: MqttV3SubAckReturnCode.SuccessMaximumQoS2, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
