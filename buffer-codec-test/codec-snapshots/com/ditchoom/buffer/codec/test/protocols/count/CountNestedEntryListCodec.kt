package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CountNestedEntryListCodec : Codec<CountNestedEntryList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountNestedEntryList {
    val __entriesCount = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val entries = ArrayList<CountNestedEntry>(__entriesCount.coerceIn(0, 1_024))
    repeat(__entriesCount) {
      entries += CountNestedEntryCodec.decode(buffer, context)
    }
    return CountNestedEntryList(entries = entries)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountNestedEntryList,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.entries.size.toUInt(), context)
    for (__elem in value.entries) {
      CountNestedEntryCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: CountNestedEntryList, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
