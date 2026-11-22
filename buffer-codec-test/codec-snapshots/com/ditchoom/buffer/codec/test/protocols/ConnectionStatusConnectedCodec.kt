package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ConnectionStatusConnectedCodec : Codec<ConnectionStatus.Connected> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ConnectionStatus.Connected = ConnectionStatus.Connected

  override fun encode(
    buffer: WriteBuffer,
    `value`: ConnectionStatus.Connected,
    context: EncodeContext,
  ) {
  }

  override fun wireBytes(`value`: ConnectionStatus.Connected, context: EncodeContext): WireByteCount = WireByteCount.exact(0L)

  override fun sizeHint(`value`: ConnectionStatus.Connected, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 0L)
}
