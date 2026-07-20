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

public object UnsignedMagicFrameFirstCodec : Codec<UnsignedMagicFrame.First> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): UnsignedMagicFrame.First {
    val magicRaw = buffer.readLong()
    val magic = UnsignedMagic((if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) magicRaw else swapBytes(magicRaw)).toULong())
    val payload = buffer.readInt()
    return UnsignedMagicFrame.First(magic = magic, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: UnsignedMagicFrame.First,
    context: EncodeContext,
  ) {
    val magicRaw = value.magic.raw.toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) magicRaw else swapBytes(magicRaw))
    buffer.writeInt(value.payload)
  }

  override fun wireSize(`value`: UnsignedMagicFrame.First, context: EncodeContext): WireSize = WireSize.Exact(12)

  override fun sizeHint(`value`: UnsignedMagicFrame.First, context: EncodeContext): Int = 12

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 12) PeekResult.Complete(12) else PeekResult.NeedsMoreData
}
