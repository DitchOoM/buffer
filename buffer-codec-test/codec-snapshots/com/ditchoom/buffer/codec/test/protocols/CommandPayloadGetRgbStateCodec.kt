package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CommandPayloadGetRgbStateCodec : Codec<CommandPayload.GetRgbState> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CommandPayload.GetRgbState = CommandPayload.GetRgbState

  override fun encode(
    buffer: WriteBuffer,
    `value`: CommandPayload.GetRgbState,
    context: EncodeContext,
  ) {
  }

  override fun wireBytes(`value`: CommandPayload.GetRgbState, context: EncodeContext): WireByteCount = WireByteCount.exact(0L)

  override fun sizeHint(`value`: CommandPayload.GetRgbState, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 0L)
}
