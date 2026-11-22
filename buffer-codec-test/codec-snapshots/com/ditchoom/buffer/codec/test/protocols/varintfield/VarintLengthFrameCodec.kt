package com.ditchoom.buffer.codec.test.protocols.varintfield

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: VarintLengthFrame, context: EncodeContext): WireByteCount {
    val __valueSize = QuicVarintCodec.encodedLength(value.value).toLong()
    return WireByteCount.exact(1L + __valueSize)
  }

  override fun sizeHint(`value`: VarintLengthFrame, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __valueWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 1L + __valueWidth)
  }
}
