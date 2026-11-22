package com.ditchoom.buffer.codec.test.protocols.mqttv5.disconnectrc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5DisconnectReasonCodeDisconnectWithWillMessageCodec : Codec<V5DisconnectReasonCode.DisconnectWithWillMessage> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5DisconnectReasonCode.DisconnectWithWillMessage {
    val id = V5DisconnectReasonCodeRaw(buffer.readUByte())
    return V5DisconnectReasonCode.DisconnectWithWillMessage(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5DisconnectReasonCode.DisconnectWithWillMessage,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5DisconnectReasonCode.DisconnectWithWillMessage, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: V5DisconnectReasonCode.DisconnectWithWillMessage, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
