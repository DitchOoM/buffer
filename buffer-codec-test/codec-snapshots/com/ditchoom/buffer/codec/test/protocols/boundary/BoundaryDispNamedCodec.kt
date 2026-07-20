package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object BoundaryDispNamedCodec : Codec<BoundaryDisp.Named> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BoundaryDisp.Named {
    val namePrefixB0 = buffer.readUByte().toUInt()
    val namePrefixB1 = buffer.readUByte().toUInt()
    val namePrefix = ((namePrefixB0 shl 8) or namePrefixB1)
    if (namePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "Named.name", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = namePrefix.toString())
    }
    val nameLength = namePrefix.toInt()
    val name = buffer.readString(nameLength, Charset.UTF8)
    return BoundaryDisp.Named(name = name)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BoundaryDisp.Named,
    context: EncodeContext,
  ) {
    val nameSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val nameBodyStart = buffer.position()
    buffer.writeString(value.name, Charset.UTF8)
    val nameEndPosition = buffer.position()
    val nameByteCount = nameEndPosition - nameBodyStart
    if (nameByteCount > 65_535) {
      throw EncodeException(fieldPath = "Named.name", reason = """UTF-8 byte length ${nameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(nameSizePosition)
    val namePrefix = nameByteCount.toUInt()
    buffer.writeUByte(((namePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((namePrefix and 0xFFu).toUByte())
    buffer.position(nameEndPosition)
  }

  override fun wireSize(`value`: BoundaryDisp.Named, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val namePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val namePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val namePrefix = ((namePrefixB0 shl 8) or namePrefixB1).toUInt()
    if (namePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "Named.name", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + namePrefix.toInt()}""")
    }
    __offset += 2 + namePrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
