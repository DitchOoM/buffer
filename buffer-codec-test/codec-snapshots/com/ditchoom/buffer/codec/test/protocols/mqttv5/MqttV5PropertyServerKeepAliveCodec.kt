package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object MqttV5PropertyServerKeepAliveCodec : Codec<MqttV5Property.ServerKeepAlive> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.ServerKeepAlive {
    val id = MqttV5PropertyId(buffer.readUByte())
    val secondsRaw = buffer.readShort()
    val seconds = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) secondsRaw else swapBytes(secondsRaw)).toUShort()
    return MqttV5Property.ServerKeepAlive(id = id, seconds = seconds)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.ServerKeepAlive,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val secondsRaw = value.seconds.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) secondsRaw else swapBytes(secondsRaw))
  }

  override fun wireBytes(`value`: MqttV5Property.ServerKeepAlive, context: EncodeContext): WireByteCount = WireByteCount.exact(3L)

  override fun sizeHint(`value`: MqttV5Property.ServerKeepAlive, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 3L)
}
