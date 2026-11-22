package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: TlsHandshakeSealedBody.ClientHello, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: TlsHandshakeSealedBody.ClientHello, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
