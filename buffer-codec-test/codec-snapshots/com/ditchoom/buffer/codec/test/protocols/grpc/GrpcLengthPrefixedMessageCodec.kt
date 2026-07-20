package com.ditchoom.buffer.codec.test.protocols.grpc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object GrpcLengthPrefixedMessageCodec : Codec<GrpcLengthPrefixedMessage> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): GrpcLengthPrefixedMessage {
    val compressedFlag = buffer.readUByte()
    val messagePrefixB0 = buffer.readUByte().toUInt()
    val messagePrefixB1 = buffer.readUByte().toUInt()
    val messagePrefixB2 = buffer.readUByte().toUInt()
    val messagePrefixB3 = buffer.readUByte().toUInt()
    val messagePrefix = ((messagePrefixB0 shl 24) or (messagePrefixB1 shl 16) or (messagePrefixB2 shl 8) or messagePrefixB3)
    if (messagePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "GrpcLengthPrefixedMessage.message", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = messagePrefix.toString())
    }
    val messageLength = messagePrefix.toInt()
    val __messageOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + messageLength)
    val message = try {
      BinaryDataCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(__messageOuterLimit)
    }
    return GrpcLengthPrefixedMessage(compressedFlag = compressedFlag, message = message)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: GrpcLengthPrefixedMessage,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.compressedFlag)
    val messageSizePosition = buffer.position()
    repeat(4) { buffer.writeUByte(0u) }
    val messageBodyStart = buffer.position()
    BinaryDataCodec.encode(buffer, value.message, context)
    val messageEndPosition = buffer.position()
    val messageByteCount = messageEndPosition - messageBodyStart
    buffer.position(messageSizePosition)
    val messagePrefix = messageByteCount.toUInt()
    buffer.writeUByte(((messagePrefix shr 24) and 0xFFu).toUByte())
    buffer.writeUByte(((messagePrefix shr 16) and 0xFFu).toUByte())
    buffer.writeUByte(((messagePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((messagePrefix and 0xFFu).toUByte())
    buffer.position(messageEndPosition)
  }

  override fun wireSize(`value`: GrpcLengthPrefixedMessage, context: EncodeContext): WireSize {
    val __messageSize = when (val __s = BinaryDataCodec.wireSize(value.message, context)) {
      is WireSize.Exact -> 4 + __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(1 + __messageSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    val messagePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val messagePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val messagePrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val messagePrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val messagePrefix = ((messagePrefixB0 shl 24) or (messagePrefixB1 shl 16) or (messagePrefixB2 shl 8) or messagePrefixB3).toUInt()
    if (messagePrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw DecodeException(fieldPath = "GrpcLengthPrefixedMessage.message", bufferPosition = baseOffset + __offset, expected = "__offset + 4 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 4 + messagePrefix.toInt()}""")
    }
    __offset += 4 + messagePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
