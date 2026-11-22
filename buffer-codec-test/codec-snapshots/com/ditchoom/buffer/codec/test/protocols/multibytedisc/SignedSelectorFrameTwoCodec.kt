package com.ditchoom.buffer.codec.test.protocols.multibytedisc

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object SignedSelectorFrameTwoCodec : Codec<SignedSelectorFrame.Two> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedSelectorFrame.Two {
    val selectorRaw = buffer.readLong()
    val selector = SignedSelector(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) selectorRaw else swapBytes(selectorRaw))
    val payload = buffer.readInt()
    return SignedSelectorFrame.Two(selector = selector, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedSelectorFrame.Two,
    context: EncodeContext,
  ) {
    val selectorRaw = value.selector.raw
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) selectorRaw else swapBytes(selectorRaw))
    buffer.writeInt(value.payload)
  }

  override fun wireBytes(`value`: SignedSelectorFrame.Two, context: EncodeContext): WireByteCount = WireByteCount.exact(12L)

  override fun sizeHint(`value`: SignedSelectorFrame.Two, context: EncodeContext): Int = 12

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 12L)
}
