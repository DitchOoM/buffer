package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MqttV5PropertyIdCodec : Codec<MqttV5PropertyId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5PropertyId {
    val raw = buffer.readUByte()
    return MqttV5PropertyId(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttV5PropertyId,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireSize(`value`: MqttV5PropertyId, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: MqttV5PropertyId, context: EncodeContext): Int = 1

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(1, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
