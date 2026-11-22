package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object CountNestedEntryListCodec : Codec<CountNestedEntryList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountNestedEntryList {
    val __entriesCount = declaredFrameBytes(0, UnsignedVarIntCodec.decode(buffer, context).toLong())
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

  override fun wireBytes(`value`: CountNestedEntryList, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountNestedEntryList, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountNestedEntryList, context: EncodeContext): Int {
    var __hint = 1
    for (__elem in value.entries) {
      __hint += CountNestedEntryCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
