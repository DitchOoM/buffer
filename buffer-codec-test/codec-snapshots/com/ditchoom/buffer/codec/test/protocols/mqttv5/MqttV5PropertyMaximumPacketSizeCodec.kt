package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: MqttV5Property.MaximumPacketSize, context: EncodeContext): WireSize = WireSize.Exact(5)

  override fun sizeHint(`value`: MqttV5Property.MaximumPacketSize, context: EncodeContext): Int = 5

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 5) PeekResult.Complete(5) else PeekResult.NeedsMoreData
}
