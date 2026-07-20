package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec : Codec<V5SubAckReasonCode.WildcardSubscriptionsNotSupported> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5SubAckReasonCode.WildcardSubscriptionsNotSupported {
    val id = V5SubAckReasonCodeRaw(buffer.readUByte())
    return V5SubAckReasonCode.WildcardSubscriptionsNotSupported(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireSize(`value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
