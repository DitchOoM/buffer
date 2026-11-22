package com.ditchoom.buffer.codec.test.protocols.visibility

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: InternalCommand.Ping, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  override fun sizeHint(`value`: InternalCommand.Ping, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 8L)
}
