package com.ditchoom.buffer.codec.test.protocols.tcp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TcpSegmentByFlagsFinAckCodec : Codec<TcpSegmentByFlags.FinAck> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TcpSegmentByFlags.FinAck {
    val flags = TcpFlagsByte(buffer.readUByte())
    return TcpSegmentByFlags.FinAck(flags = flags)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TcpSegmentByFlags.FinAck,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.flags.raw)
  }

  override fun wireSize(`value`: TcpSegmentByFlags.FinAck, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: TcpSegmentByFlags.FinAck, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
