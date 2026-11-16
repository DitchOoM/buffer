package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
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

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(0, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
