package com.ditchoom.buffer.codec.test.protocols.mqttv5.connack

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

public object V5ConnectReasonCodeQuotaExceededCodec : Codec<V5ConnectReasonCode.QuotaExceeded> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5ConnectReasonCode.QuotaExceeded {
    val id = V5ConnectReasonCodeRaw(buffer.readUByte())
    return V5ConnectReasonCode.QuotaExceeded(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5ConnectReasonCode.QuotaExceeded,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireSize(`value`: V5ConnectReasonCode.QuotaExceeded, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: V5ConnectReasonCode.QuotaExceeded, context: EncodeContext): Int = 1

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(1, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
