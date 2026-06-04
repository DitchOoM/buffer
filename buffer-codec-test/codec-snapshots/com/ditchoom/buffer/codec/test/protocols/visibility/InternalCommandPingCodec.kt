package com.ditchoom.buffer.codec.test.protocols.visibility

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

internal object InternalCommandPingCodec : Codec<InternalCommand.Ping> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): InternalCommand.Ping {
    val ts = buffer.readLong()
    return InternalCommand.Ping(ts = ts)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: InternalCommand.Ping,
    context: EncodeContext,
  ) {
    buffer.writeLong(value.ts)
  }

  override fun wireSize(`value`: InternalCommand.Ping, context: EncodeContext): WireSize = WireSize.Exact(8)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 8) PeekResult.Complete(8) else PeekResult.NeedsMoreData
}
