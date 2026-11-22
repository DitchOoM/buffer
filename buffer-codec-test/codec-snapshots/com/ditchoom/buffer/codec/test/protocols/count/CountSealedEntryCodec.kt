package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object CountSealedEntryCodec : Codec<CountSealedEntry> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountSealedEntry {
    val idRaw = buffer.readShort()
    val id = (if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) idRaw else swapBytes(idRaw)).toUShort()
    val badge = CountBadgeCodec.decode(buffer, context)
    return CountSealedEntry(id = id, badge = badge)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountSealedEntry,
    context: EncodeContext,
  ) {
    val idRaw = value.id.toShort()
    buffer.writeShort(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) idRaw else swapBytes(idRaw))
    CountBadgeCodec.encode(buffer, value.badge, context)
  }

  override fun wireBytes(`value`: CountSealedEntry, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: CountSealedEntry, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: CountSealedEntry, context: EncodeContext): Int = 2 + CountBadgeCodec.sizeHint(value.badge, context)

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
