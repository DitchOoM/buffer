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

public object SignedTagFrameBetaCodec : Codec<SignedTagFrame.Beta> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedTagFrame.Beta {
    val tagRaw = buffer.readInt()
    val tag = SignedTag(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) tagRaw else swapBytes(tagRaw))
    val value = buffer.readShort()
    return SignedTagFrame.Beta(tag = tag, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedTagFrame.Beta,
    context: EncodeContext,
  ) {
    val tagRaw = value.tag.raw
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) tagRaw else swapBytes(tagRaw))
    buffer.writeShort(value.value)
  }

  override fun wireSize(`value`: SignedTagFrame.Beta, context: EncodeContext): WireSize = WireSize.Exact(6)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 6) PeekResult.Complete(6) else PeekResult.NeedsMoreData
}
