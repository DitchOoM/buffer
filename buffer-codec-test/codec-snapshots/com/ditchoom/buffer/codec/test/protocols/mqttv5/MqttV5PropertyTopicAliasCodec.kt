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

public object MqttV5PropertyTopicAliasCodec : Codec<MqttV5Property.TopicAlias> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.TopicAlias {
    val id = MqttV5PropertyId(buffer.readUByte())
    val valueB0 = buffer.readUByte().toUInt()
    val valueB1 = buffer.readUByte().toUInt()
    val value = ((valueB0 shl 8) or valueB1).toUShort()
    return MqttV5Property.TopicAlias(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.TopicAlias,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(((value.value.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.value.toUInt() and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: MqttV5Property.TopicAlias, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
