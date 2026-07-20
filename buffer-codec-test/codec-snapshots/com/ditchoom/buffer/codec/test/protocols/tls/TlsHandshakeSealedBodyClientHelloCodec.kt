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

public object TlsHandshakeSealedBodyClientHelloCodec : Codec<TlsHandshakeSealedBody.ClientHello> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshakeSealedBody.ClientHello {
    val legacyVersion = buffer.readUShort()
    return TlsHandshakeSealedBody.ClientHello(legacyVersion = legacyVersion)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshakeSealedBody.ClientHello,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.legacyVersion)
  }

  override fun wireSize(`value`: TlsHandshakeSealedBody.ClientHello, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: TlsHandshakeSealedBody.ClientHello, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
