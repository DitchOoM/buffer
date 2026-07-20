package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: PropertyEntry, context: EncodeContext): WireSize = WireSize.Exact(5)

  override fun sizeHint(`value`: PropertyEntry, context: EncodeContext): Int = 5

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 5) PeekResult.Complete(5) else PeekResult.NeedsMoreData
}
