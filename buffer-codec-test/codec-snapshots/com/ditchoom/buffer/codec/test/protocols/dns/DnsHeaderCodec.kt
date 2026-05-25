package com.ditchoom.buffer.codec.test.protocols.dns

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object DnsHeaderCodec : Codec<DnsHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DnsHeader {
    val idB0 = buffer.readUByte().toUInt()
    val idB1 = buffer.readUByte().toUInt()
    val id = ((idB0 shl 8) or idB1).toUShort()
    val flagsB0 = buffer.readUByte().toUInt()
    val flagsB1 = buffer.readUByte().toUInt()
    val flags = ((flagsB0 shl 8) or flagsB1).toUShort()
    val qdCountB0 = buffer.readUByte().toUInt()
    val qdCountB1 = buffer.readUByte().toUInt()
    val qdCount = ((qdCountB0 shl 8) or qdCountB1).toUShort()
    val anCountB0 = buffer.readUByte().toUInt()
    val anCountB1 = buffer.readUByte().toUInt()
    val anCount = ((anCountB0 shl 8) or anCountB1).toUShort()
    val nsCountB0 = buffer.readUByte().toUInt()
    val nsCountB1 = buffer.readUByte().toUInt()
    val nsCount = ((nsCountB0 shl 8) or nsCountB1).toUShort()
    val arCountB0 = buffer.readUByte().toUInt()
    val arCountB1 = buffer.readUByte().toUInt()
    val arCount = ((arCountB0 shl 8) or arCountB1).toUShort()
    return DnsHeader(id = id, flags = flags, qdCount = qdCount, anCount = anCount, nsCount = nsCount, arCount = arCount)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DnsHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte(((value.id.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.id.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.flags.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.flags.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.qdCount.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.qdCount.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.anCount.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.anCount.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.nsCount.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.nsCount.toUInt() and 0xFFu).toUByte())
    buffer.writeUByte(((value.arCount.toUInt() shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((value.arCount.toUInt() and 0xFFu).toUByte())
  }

  override fun wireSize(`value`: DnsHeader, context: EncodeContext): WireSize = WireSize.Exact(12)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 12) PeekResult.Complete(12) else PeekResult.NeedsMoreData
}
