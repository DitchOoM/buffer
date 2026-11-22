package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: Command.Ping, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: Command.Ping, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: Command.Ping, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(8L)

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
