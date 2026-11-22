package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: PacketId, context: EncodeContext): WireByteCount = WireByteCount.exact(2L)

  override fun sizeHint(`value`: PacketId, context: EncodeContext): Int = 2

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 2L)
}
