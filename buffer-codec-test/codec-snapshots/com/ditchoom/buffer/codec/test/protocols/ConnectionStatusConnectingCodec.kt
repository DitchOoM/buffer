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

public object ConnectionStatusConnectingCodec : Codec<ConnectionStatus.Connecting> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ConnectionStatus.Connecting {
    val attempt = buffer.readUByte()
    return ConnectionStatus.Connecting(attempt = attempt)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ConnectionStatus.Connecting,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.attempt)
  }

  override fun wireBytes(`value`: ConnectionStatus.Connecting, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: ConnectionStatus.Connecting, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
