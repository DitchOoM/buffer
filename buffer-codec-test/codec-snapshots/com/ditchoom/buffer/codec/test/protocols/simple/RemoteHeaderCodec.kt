package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: RemoteHeader, context: EncodeContext): WireSize = WireSize.Exact(7 + value.payloadLength.toInt())

  override fun sizeHint(`value`: RemoteHeader, context: EncodeContext): Int = 7 + value.payload.length

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return FramePeek.needsMoreData
    val payloadLengthB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val payloadLengthB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val payloadLength = ((payloadLengthB0 shl 8) or payloadLengthB1).toUInt().toUShort()
    __offset += 2
    if (stream.available() - baseOffset < __offset + 1) return FramePeek.needsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 4) return FramePeek.needsMoreData
    __offset += 4
    val payloadBytes = payloadLength.toInt()
    if (payloadBytes < 0 || payloadBytes > Int.MAX_VALUE - __offset) {
      throw DecodeException(fieldPath = "RemoteHeader.payload", bufferPosition = baseOffset + __offset, expected = "__offset + @LengthFrom source in 0..${'$'}{Int.MAX_VALUE}", actual = """${__offset.toLong() + payloadBytes.toLong()}""")
    }
    if (stream.available() - baseOffset < __offset + payloadBytes) return FramePeek.declaredIncomplete(__offset + payloadBytes)
    __offset += payloadBytes
    return FramePeek.forDeclaredSize(__offset, stream.available() - baseOffset)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
