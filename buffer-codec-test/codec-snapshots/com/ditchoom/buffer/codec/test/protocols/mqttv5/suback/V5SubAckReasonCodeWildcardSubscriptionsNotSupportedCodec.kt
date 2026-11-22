package com.ditchoom.buffer.codec.test.protocols.mqttv5.suback

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

public object V5SubAckReasonCodeWildcardSubscriptionsNotSupportedCodec : Codec<V5SubAckReasonCode.WildcardSubscriptionsNotSupported> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): V5SubAckReasonCode.WildcardSubscriptionsNotSupported {
    val id = V5SubAckReasonCodeRaw(buffer.readUByte())
    return V5SubAckReasonCode.WildcardSubscriptionsNotSupported(id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id.raw)
  }

  override fun wireBytes(`value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: V5SubAckReasonCode.WildcardSubscriptionsNotSupported, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
