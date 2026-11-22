package com.ditchoom.buffer.codec.test.protocols.batch

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

public object LittleTagCodec : Codec<LittleTag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LittleTag {
    val rawRaw = buffer.readShort()
    val raw = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw)).toUShort()
    return LittleTag(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LittleTag,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: LittleTag, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: LittleTag, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
