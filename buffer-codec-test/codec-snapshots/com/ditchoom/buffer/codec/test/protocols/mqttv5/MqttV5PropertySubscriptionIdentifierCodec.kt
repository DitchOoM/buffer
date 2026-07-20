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

public object MqttV5PropertySubscriptionIdentifierCodec : Codec<MqttV5Property.SubscriptionIdentifier> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Property.SubscriptionIdentifier {
    val id = MqttV5PropertyId(buffer.readUByte())
    val value = VariableByteIntegerCodec.decode(buffer, context)
    return MqttV5Property.SubscriptionIdentifier(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5Property.SubscriptionIdentifier,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
    VariableByteIntegerCodec.encode(buffer, value.value, context)
  }

  override fun wireSize(`value`: MqttV5Property.SubscriptionIdentifier, context: EncodeContext): WireSize {
    val __valueSize = when (val __s = VariableByteIntegerCodec.wireSize(value.value, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(1 + __valueSize)
  }

  override fun sizeHint(`value`: MqttV5Property.SubscriptionIdentifier, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
