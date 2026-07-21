package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object DeferredDispatchFrameStatusCodec : Codec<DeferredDispatchFrame.Status> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DeferredDispatchFrame.Status {
    val code = buffer.readUByte()
    return DeferredDispatchFrame.Status(code = code)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DeferredDispatchFrame.Status,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.code)
  }

  override fun wireSize(`value`: DeferredDispatchFrame.Status, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: DeferredDispatchFrame.Status, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
