package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object BoundaryDispInheritsCodec : Codec<BoundaryDisp.Inherits> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BoundaryDisp.Inherits = BoundaryDisp.Inherits

  override fun encode(
    buffer: WriteBuffer,
    `value`: BoundaryDisp.Inherits,
    context: EncodeContext,
  ) {
  }

  override fun wireSize(`value`: BoundaryDisp.Inherits, context: EncodeContext): WireSize = WireSize.Exact(0)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 0) PeekResult.Complete(0) else PeekResult.NeedsMoreData
}
