package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

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

public object ZigZagFrameCodec : Codec<ZigZagFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ZigZagFrame {
    val id = buffer.readInt()
    val value = ZigZagUIntCodec.decode(buffer, context)
    return ZigZagFrame(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ZigZagFrame,
    context: EncodeContext,
  ) {
    buffer.writeInt(value.id)
    ZigZagUIntCodec.encode(buffer, value.value, context)
  }

  override fun wireBytes(`value`: ZigZagFrame, context: EncodeContext): WireByteCount {
    val __valueSize = ZigZagUIntCodec.wireBytes(value.value, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(4L + __valueSize)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: ZigZagFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: ZigZagFrame, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
