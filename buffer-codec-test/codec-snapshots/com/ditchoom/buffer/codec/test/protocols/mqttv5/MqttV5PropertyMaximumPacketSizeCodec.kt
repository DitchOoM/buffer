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

public object MqttV5PropertyMaximumPacketSizeCodec : Codec<MqttV5Property.MaximumPacketSize> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.MaximumPacketSize {
    val id = MqttV5PropertyId(buffer.readUByte())
    val valueB0 = buffer.readUByte().toUInt()
    val valueB1 = buffer.readUByte().toUInt()
    val valueB2 = buffer.readUByte().toUInt()
    val valueB3 = buffer.readUByte().toUInt()
    val value = ((valueB0 shl 24) or (valueB1 shl 16) or (valueB2 shl 8) or valueB3)
    return MqttV5Property.MaximumPacketSize(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.MaximumPacketSize,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(((value.value shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.value shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.value shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.value and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: MqttV5Property.MaximumPacketSize, context: EncodeContext): WireSize = WireSize.Exact(5)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 5) PeekResult.Complete(5) else PeekResult.NeedsMoreData
}
