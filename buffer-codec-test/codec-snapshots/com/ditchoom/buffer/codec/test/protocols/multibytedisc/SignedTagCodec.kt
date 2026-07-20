package com.ditchoom.buffer.codec.test.protocols.multibytedisc

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object SignedTagCodec : Codec<SignedTag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedTag {
    val rawRaw = buffer.readInt()
    val raw = if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw)
    return SignedTag(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedTag,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireSize(`value`: SignedTag, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun sizeHint(`value`: SignedTag, context: EncodeContext): Int = 4

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
