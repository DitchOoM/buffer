package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object WsMaskingKeyCodec : Codec<WsMaskingKey> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsMaskingKey {
    val raw = buffer.readUInt()
    return WsMaskingKey(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsMaskingKey,
    context: EncodeContext,
  ) {
    buffer.writeUInt(value.raw)
  }

  override fun wireSize(`value`: WsMaskingKey, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
