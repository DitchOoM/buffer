package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5PubAckReasonCodePayloadFormatInvalidCodec : Codec<V5PubAckReasonCode.PayloadFormatInvalid> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5PubAckReasonCode.PayloadFormatInvalid {
    val id = V5PubAckReasonCodeRaw(buffer.readUByte())
    return V5PubAckReasonCode.PayloadFormatInvalid(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5PubAckReasonCode.PayloadFormatInvalid,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireSize(`value`: V5PubAckReasonCode.PayloadFormatInvalid, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
