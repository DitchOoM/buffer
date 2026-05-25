package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object LeHeaderCodec : Codec<LeHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): LeHeader {
    val rawB0 = buffer.readUByte().toUInt()
    val rawB1 = buffer.readUByte().toUInt()
    val raw = (rawB0 or (rawB1 shl 8)).toUShort()
    return LeHeader(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: LeHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte((value.raw.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.raw.toUInt() shr 8) and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: LeHeader, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
