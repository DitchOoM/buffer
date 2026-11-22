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

public object MqttV5PropertyMessageExpiryIntervalCodec : Codec<MqttV5Property.MessageExpiryInterval> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.MessageExpiryInterval {
    val id = MqttV5PropertyId(buffer.readUByte())
    val secondsRaw = buffer.readInt()
    val seconds = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) secondsRaw else swapBytes(secondsRaw)).toUInt()
    return MqttV5Property.MessageExpiryInterval(id = id, seconds = seconds)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.MessageExpiryInterval,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val secondsRaw = value.seconds.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) secondsRaw else swapBytes(secondsRaw))
  }

  override fun wireBytes(`value`: MqttV5Property.MessageExpiryInterval, context: EncodeContext): WireByteCount = WireByteCount.exact(5L)

  override fun sizeHint(`value`: MqttV5Property.MessageExpiryInterval, context: EncodeContext): Int = 5

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 5L)
}
