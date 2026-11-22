package com.ditchoom.buffer.codec.test.protocols.multibytedisc

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object SignedTagFrameAlphaCodec : Codec<SignedTagFrame.Alpha> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedTagFrame.Alpha {
    val tagRaw = buffer.readInt()
    val tag = SignedTag(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) tagRaw else swapBytes(tagRaw))
    val value = buffer.readShort()
    return SignedTagFrame.Alpha(tag = tag, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedTagFrame.Alpha,
    context: EncodeContext,
  ) {
    val tagRaw = value.tag.raw
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) tagRaw else swapBytes(tagRaw))
    buffer.writeShort(value.value)
  }

  override fun wireBytes(`value`: SignedTagFrame.Alpha, context: EncodeContext): WireByteCount = WireByteCount.exact(6L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SignedTagFrame.Alpha, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SignedTagFrame.Alpha, context: EncodeContext): Int = 6

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 6L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
