package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TlsHandshakeSealedBodyEndOfEarlyDataCodec : Codec<TlsHandshakeSealedBody.EndOfEarlyData> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshakeSealedBody.EndOfEarlyData = TlsHandshakeSealedBody.EndOfEarlyData

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshakeSealedBody.EndOfEarlyData,
    context: EncodeContext,
  ) {
  }

  override fun wireSize(`value`: TlsHandshakeSealedBody.EndOfEarlyData, context: EncodeContext): WireSize = WireSize.Exact(0)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 0) PeekResult.Complete(0) else PeekResult.NeedsMoreData
}
