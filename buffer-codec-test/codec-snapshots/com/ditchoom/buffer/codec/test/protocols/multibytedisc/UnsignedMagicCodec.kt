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

public object UnsignedMagicCodec : Codec<UnsignedMagic> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): UnsignedMagic {
    val rawRaw = buffer.readLong()
    val raw = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw)).toULong()
    return UnsignedMagic(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: UnsignedMagic,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw.toLong()
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: UnsignedMagic, context: EncodeContext): WireByteCount = WireByteCount.exact(8L)

  override fun sizeHint(`value`: UnsignedMagic, context: EncodeContext): Int = 8

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 8L)
}
