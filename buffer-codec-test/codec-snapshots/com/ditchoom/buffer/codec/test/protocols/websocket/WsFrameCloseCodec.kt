package com.ditchoom.buffer.codec.test.protocols.websocket

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

public object WsFrameCloseCodec : Codec<WsFrame.Close> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrame.Close {
    val byte1 = FrameHeaderByte1(buffer.readUByte())
    val byte2 = WsHeaderByte2(buffer.readUByte())
    val extendedLength16: UShort? = if (byte2.extended16) buffer.readUShort() else null
    val extendedLength64: Long? = if (byte2.extended64) buffer.readLong() else null
    val maskingKey: WsMaskingKey? = if (byte2.masked) WsMaskingKey(buffer.readUInt()) else null
    val body: WsCloseBody? = if (buffer.remaining() >= 2) WsCloseBodyCodec.decode(buffer, context) else null
    return WsFrame.Close(byte1 = byte1, byte2 = byte2, extendedLength16 = extendedLength16, extendedLength64 = extendedLength64, maskingKey = maskingKey, body = body)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsFrame.Close,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.byte1.raw)
    buffer.writeUByte(value.byte2.raw)
    if (value.byte2.extended16) {
      val extendedLength16Value = value.extendedLength16 ?: throw EncodeException(fieldPath = "Close.extendedLength16", reason = "@When(\"byte2.extended16\") predicate is true but field is null")
      buffer.writeUShort(extendedLength16Value)
    }
    if (value.byte2.extended64) {
      val extendedLength64Value = value.extendedLength64 ?: throw EncodeException(fieldPath = "Close.extendedLength64", reason = "@When(\"byte2.extended64\") predicate is true but field is null")
      buffer.writeLong(extendedLength64Value)
    }
    if (value.byte2.masked) {
      val maskingKeyValue = value.maskingKey ?: throw EncodeException(fieldPath = "Close.maskingKey", reason = "@When(\"byte2.masked\") predicate is true but field is null")
      buffer.writeUInt(maskingKeyValue.raw)
    }
    if (value.body != null) {
      val bodyValue = value.body ?: throw EncodeException(fieldPath = "Close.body", reason = "@When(\"remaining >= 2\") predicate is true but field is null")
      WsCloseBodyCodec.encode(buffer, bodyValue, context)
    }
  }

  override fun wireSize(`value`: WsFrame.Close, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
