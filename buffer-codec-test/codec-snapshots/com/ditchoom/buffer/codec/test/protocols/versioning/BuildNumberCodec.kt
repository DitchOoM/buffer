package com.ditchoom.buffer.codec.test.protocols.versioning

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

public object BuildNumberCodec : Codec<BuildNumber> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BuildNumber {
    val valueRaw = buffer.readInt()
    val value = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw)
    return BuildNumber(value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BuildNumber,
    context: EncodeContext,
  ) {
    val valueRaw = value.value
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) valueRaw else swapBytes(valueRaw))
  }

  override fun wireSize(`value`: BuildNumber, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun sizeHint(`value`: BuildNumber, context: EncodeContext): Int = 4

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
