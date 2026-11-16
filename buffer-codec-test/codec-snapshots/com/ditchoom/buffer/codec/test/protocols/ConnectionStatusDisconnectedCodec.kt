package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ConnectionStatusDisconnectedCodec : Codec<ConnectionStatus.Disconnected> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ConnectionStatus.Disconnected = ConnectionStatus.Disconnected

  override fun encode(
    buffer: WriteBuffer,
    `value`: ConnectionStatus.Disconnected,
    context: EncodeContext,
  ) {
  }

  override fun wireSize(`value`: ConnectionStatus.Disconnected, context: EncodeContext): WireSize = WireSize.Exact(0)

  override fun sizeHint(`value`: ConnectionStatus.Disconnected, context: EncodeContext): Int = 0

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(0, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
