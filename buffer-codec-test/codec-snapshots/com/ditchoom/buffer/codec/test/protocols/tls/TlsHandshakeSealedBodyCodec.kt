package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: TlsHandshakeSealedBody, context: EncodeContext): WireByteCount = when (value) {
    is TlsHandshakeSealedBody.ClientHello -> WireByteCount.exact(3L)
    is TlsHandshakeSealedBody.EndOfEarlyData -> WireByteCount.exact(1L)
  }

  override fun sizeHint(`value`: TlsHandshakeSealedBody, context: EncodeContext): Int = 1 + when (value) {
    is TlsHandshakeSealedBody.ClientHello -> TlsHandshakeSealedBodyClientHelloCodec.sizeHint(value, context)
    is TlsHandshakeSealedBody.EndOfEarlyData -> TlsHandshakeSealedBodyEndOfEarlyDataCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x01 -> TlsHandshakeSealedBodyClientHelloCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x05 -> TlsHandshakeSealedBodyEndOfEarlyDataCodec.frameSize(stream, baseOffset + 1).fold(declared = { __h, __b -> FrameSize.declared(1 + __h, __b) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "TlsHandshakeSealedBody.discriminator", bufferPosition = baseOffset, expected = "one of {0x01, 0x05}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }
}
