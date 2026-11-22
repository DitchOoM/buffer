package com.ditchoom.buffer.codec.test.protocols.tls

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object TlsHandshakeCodec : Codec<TlsHandshake> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TlsHandshake {
    val msgType = buffer.readUByte()
    val lengthB0 = buffer.readUByte().toUInt()
    val lengthB1 = buffer.readUByte().toUInt()
    val lengthB2 = buffer.readUByte().toUInt()
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2)
    if (length > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val bodyBytes = length.toInt()
    if (bodyBytes > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "TlsHandshake.body",
            bufferPosition = buffer.position(),
            expected = "a " + bodyBytes + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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

  override fun wireBytes(`value`: TlsHandshake, context: EncodeContext): WireByteCount = WireByteCount.exact(4L + value.length.toLong())

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: TlsHandshake, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: TlsHandshake, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 3) return FrameSize.NeedsMoreData
    val lengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val lengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val lengthB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val length = ((lengthB0 shl 16) or (lengthB1 shl 8) or lengthB2).toUInt()
    __offset += 3
    if (length > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = length.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val bodyBytes = length.toInt()
    if (bodyBytes < 0 || bodyBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (bodyBytes < 0) bodyBytes.toLong() else __offset.toLong() + bodyBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += bodyBytes
    return FrameSize.declared(0, __offset.toLong())
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
