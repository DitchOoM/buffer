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

public object MqttV5PropertyRequestResponseInformationCodec : Codec<MqttV5Property.RequestResponseInformation> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.RequestResponseInformation {
    val id = MqttV5PropertyId(buffer.readUByte())
    val value = buffer.readUByte()
    return MqttV5Property.RequestResponseInformation(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.RequestResponseInformation,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(value.value)
  }

  override fun wireSize(`value`: MqttV5Property.RequestResponseInformation, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: MqttV5Property.RequestResponseInformation, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
