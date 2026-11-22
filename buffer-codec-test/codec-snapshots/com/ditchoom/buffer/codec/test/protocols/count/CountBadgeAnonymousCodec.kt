package com.ditchoom.buffer.codec.test.protocols.count

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

public object CountBadgeAnonymousCodec : Codec<CountBadge.Anonymous> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountBadge.Anonymous = CountBadge.Anonymous

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountBadge.Anonymous,
    context: EncodeContext,
  ) {
  }

  override fun wireBytes(`value`: CountBadge.Anonymous, context: EncodeContext): WireByteCount = WireByteCount.exact(0L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountBadge.Anonymous, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountBadge.Anonymous, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
