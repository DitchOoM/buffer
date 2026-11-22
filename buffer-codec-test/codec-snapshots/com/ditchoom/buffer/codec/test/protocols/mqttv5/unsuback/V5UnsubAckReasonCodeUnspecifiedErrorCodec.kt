package com.ditchoom.buffer.codec.test.protocols.mqttv5.unsuback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object V5UnsubAckReasonCodeUnspecifiedErrorCodec : Codec<V5UnsubAckReasonCode.UnspecifiedError> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5UnsubAckReasonCode.UnspecifiedError {
    val id = V5UnsubAckReasonCodeRaw(buffer.readUByte())
    return V5UnsubAckReasonCode.UnspecifiedError(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5UnsubAckReasonCode.UnspecifiedError,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5UnsubAckReasonCode.UnspecifiedError, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5UnsubAckReasonCode.UnspecifiedError, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: V5UnsubAckReasonCode.UnspecifiedError, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(1L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
