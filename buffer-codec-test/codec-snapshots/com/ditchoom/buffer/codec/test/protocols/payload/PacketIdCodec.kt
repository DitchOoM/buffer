package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object PacketIdCodec : Codec<PacketId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PacketId {
    val raw = buffer.readUShort()
    return PacketId(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: PacketId,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.raw)
  }

  override fun wireSize(`value`: PacketId, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 2) PeekResult.Complete(2) else PeekResult.NeedsMoreData
}
