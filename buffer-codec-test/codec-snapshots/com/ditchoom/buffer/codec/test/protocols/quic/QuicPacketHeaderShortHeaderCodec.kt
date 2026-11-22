package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: QuicPacketHeader.ShortHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: QuicPacketHeader.ShortHeader, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
