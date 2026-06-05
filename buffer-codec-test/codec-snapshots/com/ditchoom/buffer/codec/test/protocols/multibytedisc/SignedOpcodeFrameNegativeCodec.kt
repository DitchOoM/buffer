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

public object SignedOpcodeFrameNegativeCodec : Codec<SignedOpcodeFrame.Negative> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedOpcodeFrame.Negative {
    val opcodeRaw = buffer.readShort()
    val opcode = SignedOpcode(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opcodeRaw else swapBytes(opcodeRaw))
    val payload = buffer.readInt()
    return SignedOpcodeFrame.Negative(opcode = opcode, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedOpcodeFrame.Negative,
    context: EncodeContext,
  ) {
    val opcodeRaw = value.opcode.raw
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opcodeRaw else swapBytes(opcodeRaw))
    buffer.writeInt(value.payload)
  }

  override fun wireSize(`value`: SignedOpcodeFrame.Negative, context: EncodeContext): WireSize = WireSize.Exact(6)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 6) PeekResult.Complete(6) else PeekResult.NeedsMoreData
}
