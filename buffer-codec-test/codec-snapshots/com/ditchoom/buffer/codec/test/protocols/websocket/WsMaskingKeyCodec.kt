package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object WsMaskingKeyCodec : Codec<WsMaskingKey> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsMaskingKey {
    val raw = buffer.readUInt()
    return WsMaskingKey(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsMaskingKey,
    context: EncodeContext,
  ) {
    buffer.writeUInt(value.raw)
  }

  override fun wireBytes(`value`: WsMaskingKey, context: EncodeContext): WireByteCount = WireByteCount.exact(4L)

  override fun sizeHint(`value`: WsMaskingKey, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 4L)
}
