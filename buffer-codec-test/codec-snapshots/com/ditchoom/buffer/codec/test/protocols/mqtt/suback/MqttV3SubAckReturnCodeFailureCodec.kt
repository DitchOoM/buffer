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

public object MqttV3SubAckReturnCodeFailureCodec : Codec<MqttV3SubAckReturnCode.Failure> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV3SubAckReturnCode.Failure {
    buffer.readUByte()
    return MqttV3SubAckReturnCode.Failure
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV3SubAckReturnCode.Failure,
    context: EncodeContext,
  ) {
    buffer.writeUByte(0x80.toUByte())
  }

  override fun wireBytes(`value`: MqttV3SubAckReturnCode.Failure, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: MqttV3SubAckReturnCode.Failure, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
