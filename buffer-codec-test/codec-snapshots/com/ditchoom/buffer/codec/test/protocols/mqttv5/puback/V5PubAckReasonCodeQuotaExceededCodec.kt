package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5PubAckReasonCodeQuotaExceededCodec : Codec<V5PubAckReasonCode.QuotaExceeded> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5PubAckReasonCode.QuotaExceeded {
    val id = V5PubAckReasonCodeRaw(buffer.readUByte())
    return V5PubAckReasonCode.QuotaExceeded(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5PubAckReasonCode.QuotaExceeded,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5PubAckReasonCode.QuotaExceeded, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: V5PubAckReasonCode.QuotaExceeded, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
