package com.ditchoom.buffer.codec.test.protocols.varintfield

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object VarintLengthFrameCodec : Codec<VarintLengthFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): VarintLengthFrame {
    val value = QuicVarintCodec.decode(buffer, context)
    val tag = buffer.readUByte()
    return VarintLengthFrame(value = value, tag = tag)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: VarintLengthFrame,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.value, context)
    buffer.writeUByte(value.tag)
  }

  override fun wireSize(`value`: VarintLengthFrame, context: EncodeContext): WireSize {
    val __valueSize = when (val __s = QuicVarintCodec.wireSize(value.value, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(1 + __valueSize)
  }

  override fun sizeHint(`value`: VarintLengthFrame, context: EncodeContext): Int = 1

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    val __valueFrame = QuicVarintCodec.peekFrame(stream, baseOffset + 0)
    if (!__valueFrame.isComplete) {
      return __valueFrame.withoutDeclaredSize()
    }
    val __total = 0 + __valueFrame.size + 1
    return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
