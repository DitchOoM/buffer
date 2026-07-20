package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CountBadgeAnonymousCodec : Codec<CountBadge.Anonymous> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountBadge.Anonymous = CountBadge.Anonymous

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountBadge.Anonymous,
    context: EncodeContext,
  ) {
  }

  override fun wireSize(`value`: CountBadge.Anonymous, context: EncodeContext): WireSize = WireSize.Exact(0)

  override fun sizeHint(`value`: CountBadge.Anonymous, context: EncodeContext): Int = 0

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 0) PeekResult.Complete(0) else PeekResult.NeedsMoreData
}
