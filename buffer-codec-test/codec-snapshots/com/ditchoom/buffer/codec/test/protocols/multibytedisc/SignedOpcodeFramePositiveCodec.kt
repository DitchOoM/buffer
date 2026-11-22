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

public object SignedOpcodeFramePositiveCodec : Codec<SignedOpcodeFrame.Positive> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedOpcodeFrame.Positive {
    val opcodeRaw = buffer.readShort()
    val opcode = SignedOpcode(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opcodeRaw else swapBytes(opcodeRaw))
    val payload = buffer.readInt()
    return SignedOpcodeFrame.Positive(opcode = opcode, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedOpcodeFrame.Positive,
    context: EncodeContext,
  ) {
    val opcodeRaw = value.opcode.raw
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) opcodeRaw else swapBytes(opcodeRaw))
    buffer.writeInt(value.payload)
  }

  override fun wireBytes(`value`: SignedOpcodeFrame.Positive, context: EncodeContext): WireByteCount = WireByteCount.exact(6L)

  override fun sizeHint(`value`: SignedOpcodeFrame.Positive, context: EncodeContext): Int = 6

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 6L)
}
