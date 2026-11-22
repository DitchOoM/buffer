package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5SubAckReasonCodeNotAuthorizedCodec : Codec<V5SubAckReasonCode.NotAuthorized> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5SubAckReasonCode.NotAuthorized {
    val id = V5SubAckReasonCodeRaw(buffer.readUByte())
    return V5SubAckReasonCode.NotAuthorized(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5SubAckReasonCode.NotAuthorized,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5SubAckReasonCode.NotAuthorized, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: V5SubAckReasonCode.NotAuthorized, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
