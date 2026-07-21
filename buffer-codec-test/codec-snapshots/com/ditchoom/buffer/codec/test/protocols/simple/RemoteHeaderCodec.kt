package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.Charset
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

public object RemoteHeaderCodec : Codec<RemoteHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemoteHeader {
    val payloadLength = buffer.readUShort()
    val flags = buffer.readUByte()
    val correlationId = buffer.readUInt()
    val payload = buffer.readString(payloadLength.toInt(), Charset.UTF8)
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
    buffer.writeString(value.payload, Charset.UTF8)
  }

  override fun wireSize(`value`: RemoteHeader, context: EncodeContext): WireSize = WireSize.Exact(7 + value.payloadLength.toInt())

  override fun sizeHint(`value`: RemoteHeader, context: EncodeContext): Int = 7 + value.payload.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw DecodeException(fieldPath = "RemoteHeader.payload", bufferPosition = baseOffset + __offset, expected = "__offset + @LengthFrom source in 0..${'$'}{Int.MAX_VALUE}", actual = """${__offset.toLong() + payloadBytes.toLong()}""")
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
