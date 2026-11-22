package com.ditchoom.buffer.codec.test.protocols.grpc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.WireByteCount
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
      throw FrameTooLargeException(declaredBytes = messagePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val messageLength = messagePrefix.toInt()
    if (messageLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "GrpcLengthPrefixedMessage.message",
            bufferPosition = buffer.position(),
            expected = "a " + messageLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
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

  override fun wireBytes(`value`: GrpcLengthPrefixedMessage, context: EncodeContext): WireByteCount {
    val __messageBodyBytes = BinaryDataCodec.wireBytes(value.message, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    val __messageSize = 4 + __messageBodyBytes
    return WireByteCount.exact(1L + __messageSize)
  }

  override fun sizeHint(`value`: GrpcLengthPrefixedMessage, context: EncodeContext): Int = 5

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    val messagePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val messagePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val messagePrefixB2 = stream.peekByte(baseOffset + __offset + 2).toInt() and 0xFF
    val messagePrefixB3 = stream.peekByte(baseOffset + __offset + 3).toInt() and 0xFF
    val messagePrefix = ((messagePrefixB0 shl 24) or (messagePrefixB1 shl 16) or (messagePrefixB2 shl 8) or messagePrefixB3).toUInt()
    if (messagePrefix > (Int.MAX_VALUE - __offset - 4).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 4 + messagePrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 4 + messagePrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
