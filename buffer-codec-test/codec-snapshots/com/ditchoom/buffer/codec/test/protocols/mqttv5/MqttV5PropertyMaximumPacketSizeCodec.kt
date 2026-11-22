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

public object MqttV5PropertyMaximumPacketSizeCodec : Codec<MqttV5Property.MaximumPacketSize> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.MaximumPacketSize {
    val id = MqttV5PropertyId(buffer.readUByte())
    val valueRaw = buffer.readInt()
    val value = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw)).toUInt()
    return MqttV5Property.MaximumPacketSize(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.MaximumPacketSize,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    val valueRaw = value.value.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw))
  }

  override fun wireBytes(`value`: MqttV5Property.MaximumPacketSize, context: EncodeContext): WireByteCount = WireByteCount.exact(5L)

  override fun sizeHint(`value`: MqttV5Property.MaximumPacketSize, context: EncodeContext): Int = 5

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 5L)
}
