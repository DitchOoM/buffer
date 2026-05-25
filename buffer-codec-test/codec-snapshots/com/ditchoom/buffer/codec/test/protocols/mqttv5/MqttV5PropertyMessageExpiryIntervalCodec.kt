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

public object MqttV5PropertyMessageExpiryIntervalCodec : Codec<MqttV5Property.MessageExpiryInterval> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.MessageExpiryInterval {
    val id = MqttV5PropertyId(buffer.readUByte())
    val secondsB0 = buffer.readUByte().toUInt()
    val secondsB1 = buffer.readUByte().toUInt()
    val secondsB2 = buffer.readUByte().toUInt()
    val secondsB3 = buffer.readUByte().toUInt()
    val seconds = ((secondsB0 shl 24) or (secondsB1 shl 16) or (secondsB2 shl 8) or secondsB3)
    return MqttV5Property.MessageExpiryInterval(id = id, seconds = seconds)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.MessageExpiryInterval,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(((value.seconds shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((value.seconds shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.seconds shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.seconds and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: MqttV5Property.MessageExpiryInterval, context: EncodeContext): WireSize = WireSize.Exact(5)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 5) PeekResult.Complete(5) else PeekResult.NeedsMoreData
}
