package com.ditchoom.buffer.codec.test.protocols.simple

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

public object LeHeaderCodec : Codec<LeHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LeHeader {
    val rawRaw = buffer.readShort()
    val raw = (if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw)).toUShort()
    return LeHeader(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LeHeader,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.LITTLE_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: LeHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: LeHeader, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
