package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TlsHandshakeCodec : Codec<TlsHandshake> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshake {
    val msgType = buffer.readUByte()
    val lengthB0 = buffer.readUByte().toUInt()
    val lengthB1 = buffer.readUByte().toUInt()
    val lengthB2 = buffer.readUByte().toUInt()
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2)
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "TlsHandshake.body", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val bodyBytes = length.toInt()
    val bodyOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + bodyBytes)
    val body = try {
      TlsHandshakeBodyCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(bodyOuterLimit)
    }
    return TlsHandshake(msgType = msgType, length = length, body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TlsHandshake,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.msgType)
    if (value.length > ((1u shl 24) - 1u)) {
      throw EncodeException(fieldPath = "TlsHandshake.length", reason = "value exceeds @WireBytes(3) range (max 16777215)")
    }
    buffer.writeUByte(((value.length shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((value.length shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.length and 0xFFu).toUByte())
    TlsHandshakeBodyCodec.encode(buffer, value.body, context)
  }

  override fun wireSize(`value`: TlsHandshake, context: EncodeContext): WireSize = WireSize.Exact(4 + value.length.toInt())

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 3) return PeekResult.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2).toUInt()
    __offset += 3
    if (length > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "TlsHandshake.body", bufferPosition = -1, expected = "@LengthFrom source <= ${'$'}{Int.MAX_VALUE}", actual = length.toString())
    }
    val bodyBytes = length.toInt()
    if (stream.available() - baseOffset < __offset + bodyBytes) return PeekResult.NeedsMoreData
    __offset += bodyBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
