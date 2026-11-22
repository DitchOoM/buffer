package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object PropertyEntryCodec : Codec<PropertyEntry> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PropertyEntry {
    val id = buffer.readUByte()
    val value = buffer.readUInt()
    return PropertyEntry(id = id, value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: PropertyEntry,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.id)
    buffer.writeUInt(value.value)
  }

  override fun wireBytes(`value`: PropertyEntry, context: EncodeContext): WireByteCount = WireByteCount.exact(5L)

  override fun sizeHint(`value`: PropertyEntry, context: EncodeContext): Int = 5

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 5L)
}
