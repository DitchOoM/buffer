package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CommandPayloadSetRgbStateCodec : Codec<CommandPayload.SetRgbState> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CommandPayload.SetRgbState {
    val r = buffer.readUByte()
    val g = buffer.readUByte()
    val b = buffer.readUByte()
    return CommandPayload.SetRgbState(r = r, g = g, b = b)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CommandPayload.SetRgbState,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.r)
    buffer.writeUByte(value.g)
    buffer.writeUByte(value.b)
  }

  override fun wireSize(`value`: CommandPayload.SetRgbState, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
