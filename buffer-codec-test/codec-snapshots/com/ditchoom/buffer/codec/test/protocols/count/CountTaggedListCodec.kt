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
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object CountTaggedListCodec : Codec<CountTaggedList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountTaggedList {
    val __tagsCount = declaredFrameBytes(0, UnsignedVarIntCodec.decode(buffer, context).toLong())
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

  override fun wireBytes(`value`: CountTaggedList, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountTaggedList, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountTaggedList, context: EncodeContext): Int {
    var __hint = 1
    for (__elem in value.tags) {
      __hint += CountTaggedEntryCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
