package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: BoundaryDisp.Inherits, context: EncodeContext): WireByteCount = WireByteCount.exact(0L)

  override fun sizeHint(`value`: BoundaryDisp.Inherits, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 0L)
}
