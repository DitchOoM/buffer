package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RemoteHeaderCodec : Codec<RemoteHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemoteHeader {
    val payloadLength = buffer.readUShort()
    val flags = buffer.readUByte()
    val correlationId = buffer.readUInt()
    val payload = buffer.readText(payloadLength.toInt(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return RemoteHeader(payloadLength = payloadLength, flags = flags, correlationId = correlationId, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemoteHeader,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.payloadLength)
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.correlationId)
    buffer.writeText(value.payload, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: RemoteHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(7L + value.payloadLength.toLong())

  override fun sizeHint(`value`: RemoteHeader, context: EncodeContext): Int = 7 + value.payload.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return FrameSize.NeedsMoreData
    __offset += 4
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += payloadBytes
    return FrameSize.declared(0, __offset.toLong())
  }
}
