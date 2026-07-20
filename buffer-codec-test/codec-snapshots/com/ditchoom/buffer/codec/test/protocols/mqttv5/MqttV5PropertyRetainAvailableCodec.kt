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

public object MqttV5PropertyRetainAvailableCodec : Codec<MqttV5Property.RetainAvailable> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.RetainAvailable {
    val id = MqttV5PropertyId(buffer.readUByte())
    val value = buffer.readUByte()
    return MqttV5Property.RetainAvailable(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.RetainAvailable,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    buffer.writeUByte(value.value)
  }

  override fun wireSize(`value`: MqttV5Property.RetainAvailable, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: MqttV5Property.RetainAvailable, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
