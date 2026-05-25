package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV5PropertyMaximumQoSCodec : Codec<MqttV5Property.MaximumQoS> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.MaximumQoS {
    val id = MqttV5PropertyId(buffer.readUByte())
    val value = buffer.readUByte()
    return MqttV5Property.MaximumQoS(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.MaximumQoS,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(value.value)
  }

  override fun wireSize(`value`: MqttV5Property.MaximumQoS, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
