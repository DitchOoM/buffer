package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CommandPingCodec : Codec<Command.Ping> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Command.Ping {
    val ts = buffer.readLong()
    return Command.Ping(ts = ts)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Command.Ping,
    context: EncodeContext,
  ) {
    buffer.writeLong(value.ts)
  }

  override fun wireSize(`value`: Command.Ping, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun sizeHint(`value`: Command.Ping, context: EncodeContext): Int = 8

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
