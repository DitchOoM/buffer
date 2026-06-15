package com.ditchoom.buffer.codec.test.protocols.varintfield

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
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
    val __valueSize = (QuicVarintCodec.wireSize(value.value, context) as WireSize.Exact).bytes
    return WireSize.Exact(1 + __valueSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __valueFrame = QuicVarintCodec.peekFrameSize(stream, baseOffset + 0)
    if (__valueFrame !is PeekResult.Complete) {
      return __valueFrame
    }
    val __total = 0 + __valueFrame.bytes + 1
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
