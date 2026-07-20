package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object QuicPacketHeaderShortHeaderCodec : Codec<QuicPacketHeader.ShortHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): QuicPacketHeader.ShortHeader {
    val firstByte = QuicHeaderByte(buffer.readUByte())
    return QuicPacketHeader.ShortHeader(firstByte = firstByte)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: QuicPacketHeader.ShortHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.firstByte.raw)
  }

  override fun wireSize(`value`: QuicPacketHeader.ShortHeader, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: QuicPacketHeader.ShortHeader, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
