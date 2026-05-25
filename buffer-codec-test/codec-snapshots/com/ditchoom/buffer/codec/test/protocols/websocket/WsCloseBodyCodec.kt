package com.ditchoom.buffer.codec.test.protocols.websocket

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

public object WsCloseBodyCodec : Codec<WsCloseBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsCloseBody {
    val statusCodeB0 = buffer.readUByte().toUInt()
    val statusCodeB1 = buffer.readUByte().toUInt()
    val statusCode = ((statusCodeB0 shl 8) or statusCodeB1).toUShort()
    val reason = buffer.readString(buffer.remaining(), Charset.UTF8)
    return WsCloseBody(statusCode = statusCode, reason = reason)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsCloseBody,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.statusCode.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.statusCode.toUInt() and 0xFFu).toUByte())
    buffer.writeString(value.reason, Charset.UTF8)
  }

  override fun wireSize(`value`: WsCloseBody, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
