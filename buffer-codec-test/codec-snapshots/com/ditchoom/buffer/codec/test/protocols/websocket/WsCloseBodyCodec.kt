package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object WsCloseBodyCodec : Codec<WsCloseBody> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsCloseBody {
    val statusCodeRaw = buffer.readShort()
    val statusCode = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) statusCodeRaw else swapBytes(statusCodeRaw)).toUShort()
    val reason = buffer.readText(buffer.remaining(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    return WsCloseBody(statusCode = statusCode, reason = reason)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsCloseBody,
    context: EncodeContext,
  ) {
    val statusCodeRaw = value.statusCode.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) statusCodeRaw else swapBytes(statusCodeRaw))
    buffer.writeText(value.reason, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireSize(`value`: WsCloseBody, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: WsCloseBody, context: EncodeContext): Int = 2 + value.reason.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
