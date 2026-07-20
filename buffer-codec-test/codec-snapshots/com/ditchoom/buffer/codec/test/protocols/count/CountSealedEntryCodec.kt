package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

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

  override fun wireSize(`value`: CountSealedEntry, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
