package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ByteOrder
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
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object LePacketCodec : Codec<LePacket> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LePacket {
    val headerRaw = buffer.readShort()
    val header = LeHeader((if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) headerRaw else swapBytes(headerRaw)).toUShort())
    val payload = buffer.readText(header.length, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return LePacket(header = header, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LePacket,
    context: EncodeContext,
  ) {
    val headerRaw = value.header.raw.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) headerRaw else swapBytes(headerRaw))
    buffer.writeText(value.payload, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: LePacket, context: EncodeContext): WireByteCount = WireByteCount.exact(2L + value.header.length.toLong())

  override fun sizeHint(`value`: LePacket, context: EncodeContext): Int = 2 + value.payload.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val headerRawB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val headerRawB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val headerRaw = (headerRawB0 or (headerRawB1 shl 8)).toUInt().toUShort()
    val header = LeHeader(headerRaw)
    __offset += 2
    val payloadBytes = header.length
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw FrameTooLargeException(declaredBytes = if (payloadBytes < 0) payloadBytes.toLong() else __offset.toLong() + payloadBytes, maxBytes = Int.MAX_VALUE)
    }
    __offset += payloadBytes
    return FrameSize.declared(0, __offset.toLong())
  }
}
