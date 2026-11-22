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

public object SignedOpcodeCodec : Codec<SignedOpcode> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedOpcode {
    val rawRaw = buffer.readShort()
    val raw = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) rawRaw else swapBytes(rawRaw)
    return SignedOpcode(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedOpcode,
    context: EncodeContext,
  ) {
    val rawRaw = value.raw
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) rawRaw else swapBytes(rawRaw))
  }

  override fun wireBytes(`value`: SignedOpcode, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: SignedOpcode, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
