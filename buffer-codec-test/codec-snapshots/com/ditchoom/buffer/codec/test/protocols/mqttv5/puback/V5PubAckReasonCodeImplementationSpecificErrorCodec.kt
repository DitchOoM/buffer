package com.ditchoom.buffer.codec.test.protocols.mqttv5.puback

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object V5PubAckReasonCodeImplementationSpecificErrorCodec : Codec<V5PubAckReasonCode.ImplementationSpecificError> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5PubAckReasonCode.ImplementationSpecificError {
    val id = V5PubAckReasonCodeRaw(buffer.readUByte())
    return V5PubAckReasonCode.ImplementationSpecificError(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5PubAckReasonCode.ImplementationSpecificError,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5PubAckReasonCode.ImplementationSpecificError, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5PubAckReasonCode.ImplementationSpecificError, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: V5PubAckReasonCode.ImplementationSpecificError, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
