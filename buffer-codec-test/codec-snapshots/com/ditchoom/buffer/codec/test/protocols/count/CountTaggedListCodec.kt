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

public object CountTaggedListCodec : Codec<CountTaggedList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountTaggedList {
    val __tagsCount = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val tags = ArrayList<CountTaggedEntry>(__tagsCount.coerceIn(0, 1_024))
    repeat(__tagsCount) {
      tags += CountTaggedEntryCodec.decode(buffer, context)
    }
    return CountTaggedList(tags = tags)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountTaggedList,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.tags.size.toUInt(), context)
    for (__elem in value.tags) {
      CountTaggedEntryCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: CountTaggedList, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
