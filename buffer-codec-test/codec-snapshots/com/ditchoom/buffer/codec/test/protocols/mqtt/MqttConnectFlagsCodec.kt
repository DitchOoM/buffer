package com.ditchoom.buffer.codec.test.protocols.mqtt

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

public object MqttConnectFlagsCodec : Codec<MqttConnectFlags> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MqttConnectFlags {
    val raw = buffer.readUByte()
    return MqttConnectFlags(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MqttConnectFlags,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireSize(`value`: MqttConnectFlags, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: MqttConnectFlags, context: EncodeContext): Int = 1

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(1, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
