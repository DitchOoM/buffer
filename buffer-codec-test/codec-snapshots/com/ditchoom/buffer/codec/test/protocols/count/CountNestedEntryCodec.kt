package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CountNestedEntryCodec : Codec<CountNestedEntry> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountNestedEntry {
    val inner = CountInnerFixedCodec.decode(buffer, context)
    return CountNestedEntry(inner = inner)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountNestedEntry,
    context: EncodeContext,
  ) {
    CountInnerFixedCodec.encode(buffer, value.inner, context)
  }

  override fun wireSize(`value`: CountNestedEntry, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
