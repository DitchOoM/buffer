package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TlsHandshakeSealedBodyCodec : Codec<TlsHandshakeSealedBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshakeSealedBody {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x01 -> TlsHandshakeSealedBodyClientHelloCodec.decode(buffer, context)
      0x05 -> TlsHandshakeSealedBodyEndOfEarlyDataCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "TlsHandshakeSealedBody.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x01, 0x05}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshakeSealedBody,
    context: EncodeContext,
  ) {
    when (value) {
      is TlsHandshakeSealedBody.ClientHello -> {
        buffer.writeUByte(0x01.toUByte())
        TlsHandshakeSealedBodyClientHelloCodec.encode(buffer, value, context)
      }
      is TlsHandshakeSealedBody.EndOfEarlyData -> {
        buffer.writeUByte(0x05.toUByte())
        TlsHandshakeSealedBodyEndOfEarlyDataCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: TlsHandshakeSealedBody, context: EncodeContext): WireSize = when (value) {
    is TlsHandshakeSealedBody.ClientHello -> WireSize.Exact(3)
    is TlsHandshakeSealedBody.EndOfEarlyData -> WireSize.Exact(1)
  }

  override fun sizeHint(`value`: TlsHandshakeSealedBody, context: EncodeContext): Int = 1 + when (value) {
    is TlsHandshakeSealedBody.ClientHello -> TlsHandshakeSealedBodyClientHelloCodec.sizeHint(value, context)
    is TlsHandshakeSealedBody.EndOfEarlyData -> TlsHandshakeSealedBodyEndOfEarlyDataCodec.sizeHint(value, context)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> {
        when (val inner = TlsHandshakeSealedBodyClientHelloCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      0x05 -> {
        when (val inner = TlsHandshakeSealedBodyEndOfEarlyDataCodec.peekFrameSize(stream, baseOffset + 1)) {
          is PeekResult.Complete -> PeekResult.Complete(1 + inner.bytes)
          else -> inner
        }
      }
      else -> {
        throw DecodeException(fieldPath = "TlsHandshakeSealedBody.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x05}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
