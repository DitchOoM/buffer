package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV5PropertyRequestProblemInformationCodec : Codec<MqttV5Property.RequestProblemInformation> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.RequestProblemInformation {
    val id = MqttV5PropertyId(buffer.readUByte())
    val value = buffer.readUByte()
    return MqttV5Property.RequestProblemInformation(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.RequestProblemInformation,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(value.value)
  }

  override fun wireBytes(`value`: MqttV5Property.RequestProblemInformation, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: MqttV5Property.RequestProblemInformation, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
