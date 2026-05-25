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

public object MqttV5PropertyServerKeepAliveCodec : Codec<MqttV5Property.ServerKeepAlive> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.ServerKeepAlive {
    val id = MqttV5PropertyId(buffer.readUByte())
    val secondsB0 = buffer.readUByte().toUInt()
    val secondsB1 = buffer.readUByte().toUInt()
    val seconds = ((secondsB0 shl 8) or secondsB1).toUShort()
    return MqttV5Property.ServerKeepAlive(id = id, seconds = seconds)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.ServerKeepAlive,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(((value.seconds.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.seconds.toUInt() and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: MqttV5Property.ServerKeepAlive, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
