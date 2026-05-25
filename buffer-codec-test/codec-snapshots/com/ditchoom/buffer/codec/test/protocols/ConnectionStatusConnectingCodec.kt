package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: ConnectionStatus.Connecting, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
