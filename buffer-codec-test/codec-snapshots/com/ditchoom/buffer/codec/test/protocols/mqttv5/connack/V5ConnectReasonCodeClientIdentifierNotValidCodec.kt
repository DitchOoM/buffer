package com.ditchoom.buffer.codec.test.protocols.mqttv5.connack

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object V5ConnectReasonCodeClientIdentifierNotValidCodec : Codec<V5ConnectReasonCode.ClientIdentifierNotValid> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5ConnectReasonCode.ClientIdentifierNotValid {
    val id = V5ConnectReasonCodeRaw(buffer.readUByte())
    return V5ConnectReasonCode.ClientIdentifierNotValid(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5ConnectReasonCode.ClientIdentifierNotValid,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireSize(`value`: V5ConnectReasonCode.ClientIdentifierNotValid, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: V5ConnectReasonCode.ClientIdentifierNotValid, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
