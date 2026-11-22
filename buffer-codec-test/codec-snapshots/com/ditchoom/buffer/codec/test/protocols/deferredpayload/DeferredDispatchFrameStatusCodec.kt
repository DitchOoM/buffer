package com.ditchoom.buffer.codec.test.protocols.deferredpayload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: DeferredDispatchFrame.Status, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: DeferredDispatchFrame.Status, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
