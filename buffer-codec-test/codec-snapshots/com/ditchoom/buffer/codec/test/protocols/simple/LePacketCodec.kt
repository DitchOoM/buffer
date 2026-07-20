package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object LePacketCodec : Codec<LePacket> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LePacket {
    val headerRaw = buffer.readShort()
    val header = LeHeader((if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) headerRaw else swapBytes(headerRaw)).toUShort())
    val payload = buffer.readString(header.length, Charset.UTF8)
    return LePacket(header = header, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LePacket,
    context: EncodeContext,
  ) {
    val headerRaw = value.header.raw.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) headerRaw else swapBytes(headerRaw))
    buffer.writeString(value.payload, Charset.UTF8)
  }

  override fun wireSize(`value`: LePacket, context: EncodeContext): WireSize = WireSize.Exact(2 + value.header.length)

  override fun sizeHint(`value`: LePacket, context: EncodeContext): Int = 2 + value.payload.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val headerRawB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val headerRawB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val headerRaw = (headerRawB0 or (headerRawB1 shl 8)).toUInt().toUShort()
    val header = LeHeader(headerRaw)
    __offset += 2
    val payloadBytes = header.length
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
