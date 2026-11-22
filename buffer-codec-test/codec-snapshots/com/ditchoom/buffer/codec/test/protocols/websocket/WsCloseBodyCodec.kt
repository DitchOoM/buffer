package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: WsCloseBody, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: WsCloseBody, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: WsCloseBody, context: EncodeContext): Int = 2 + value.reason.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
