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

  override fun wireSize(`value`: SignedOpcode, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: SignedOpcode, context: EncodeContext): Int = 2

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
