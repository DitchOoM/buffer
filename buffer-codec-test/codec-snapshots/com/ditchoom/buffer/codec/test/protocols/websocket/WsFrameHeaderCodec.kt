package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Long
import kotlin.UShort

public object WsFrameHeaderCodec : Codec<WsFrameHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrameHeader {
    val __batch13 = buffer.readShort().toInt() and 0xFFFF
    val byte1: com.ditchoom.buffer.codec.test.protocols.websocket.FrameHeaderByte1
    val byte2: com.ditchoom.buffer.codec.test.protocols.websocket.WsHeaderByte2
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      byte1 = FrameHeaderByte1((__batch13 ushr 8 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch13 and 0xFF).toUByte())
    } else {
      byte1 = FrameHeaderByte1((__batch13 and 0xFF).toUByte())
      byte2 = WsHeaderByte2((__batch13 ushr 8 and 0xFF).toUByte())
    }
    val extendedLength16: UShort? = if (byte2.extended16) buffer.readUShort() else null
    val extendedLength64: Long? = if (byte2.extended64) buffer.readLong() else null
    val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
    return WsFrameHeader(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsFrameHeader,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeShort((((value.byte1.raw.toInt() and 0xFF) shl 8) or (value.byte2.raw.toInt() and 0xFF)).toShort())
    } else {
      buffer.writeShort(((value.byte1.raw.toInt() and 0xFF) or ((value.byte2.raw.toInt() and 0xFF) shl 8)).toShort())
    }
    if (value.byte2.extended16) {
      val extendedLength16Value = value.extendedLength16 ?: throw EncodeException(fieldPath = "WsFrameHeader.extendedLength16", reason = "@When(\"byte2.extended16\") predicate is true but field is null")
      buffer.writeUShort(extendedLength16Value)
    }
    if (value.byte2.extended64) {
      val extendedLength64Value = value.extendedLength64 ?: throw EncodeException(fieldPath = "WsFrameHeader.extendedLength64", reason = "@When(\"byte2.extended64\") predicate is true but field is null")
      buffer.writeLong(extendedLength64Value)
    }
    if (value.byte2.masked) {
      val maskingKeyValue = value.maskingKey ?: throw EncodeException(fieldPath = "WsFrameHeader.maskingKey", reason = "@When(\"byte2.masked\") predicate is true but field is null")
      buffer.writeUInt(maskingKeyValue.raw)
    }
  }

  override fun wireSize(`value`: WsFrameHeader, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    val byte2Raw = stream.peekByte(baseOffset + __offset).toUByte()
    val byte2 = WsHeaderByte2(byte2Raw)
    __offset += 1
    if (byte2.extended16) {
      if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
      __offset += 2
    }
    if (byte2.extended64) {
      if (stream.available() - baseOffset < __offset + 8) return PeekResult.NeedsMoreData
      __offset += 8
    }
    if (byte2.masked) {
      if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
      __offset += 4
    }
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
