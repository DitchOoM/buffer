package com.ditchoom.buffer.codec.test.protocols.valueclassstring

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
import kotlin.Int

public object LengthFromValueClassIdCodec : Codec<LengthFromValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LengthFromValueClassId {
    val __batch1 = buffer.readShort().toInt() and 0xFFFF
    val len: kotlin.UByte
    val flags: kotlin.Byte
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      len = (__batch1 ushr 8 and 0xFF).toUByte()
      flags = (__batch1 and 0xFF).toByte()
    } else {
      len = (__batch1 and 0xFF).toUByte()
      flags = (__batch1 ushr 8 and 0xFF).toByte()
    }
    val payload = UserId(buffer.readString(len.toInt(), Charset.UTF8))
    return LengthFromValueClassId(len = len, flags = flags, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LengthFromValueClassId,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.len.toInt() and 0xFF) shl 8) or (value.flags.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.len.toInt() and 0xFF) or ((value.flags.toInt() and 0xFF) shl 8)).toShort())
    }
    buffer.writeString(value.payload.value, Charset.UTF8)
  }

  override fun wireSize(`value`: LengthFromValueClassId, context: EncodeContext): WireSize = WireSize.Exact(2 + value.len.toInt())

  override fun sizeHint(`value`: LengthFromValueClassId, context: EncodeContext): Int = 2 + value.payload.value.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val len = stream.peekByte(baseOffset + __offset).toUByte()
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    val payloadBytes = len.toInt()
    if (stream.available() - baseOffset < __offset + payloadBytes) return PeekResult.NeedsMoreData
    __offset += payloadBytes
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
