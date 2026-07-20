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

public object BoundaryHostCodec : Codec<BoundaryHost> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): BoundaryHost {
    val hostPrefixB0 = buffer.readUByte().toUInt()
    val hostPrefixB1 = buffer.readUByte().toUInt()
    val hostPrefix = ((hostPrefixB0 shl 8) or hostPrefixB1)
    if (hostPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "BoundaryHost.host", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = hostPrefix.toString())
    }
    val hostLength = hostPrefix.toInt()
    val host = buffer.readString(hostLength, Charset.UTF8)
    val disp = BoundaryDispCodec.decode(buffer, context)
    return BoundaryHost(host = host, disp = disp)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: BoundaryHost,
    context: EncodeContext,
  ) {
    val hostSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val hostBodyStart = buffer.position()
    buffer.writeString(value.host, Charset.UTF8)
    val hostEndPosition = buffer.position()
    val hostByteCount = hostEndPosition - hostBodyStart
    if (hostByteCount > 65_535) {
      throw EncodeException(fieldPath = "BoundaryHost.host", reason = """UTF-8 byte length ${hostByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(hostSizePosition)
    val hostPrefix = hostByteCount.toUInt()
    buffer.writeUByte(((hostPrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((hostPrefix and 0xFFu).toUByte())
    buffer.position(hostEndPosition)
    BoundaryDispCodec.encode(buffer, value.disp, context)
  }

  override fun wireSize(`value`: BoundaryHost, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: BoundaryHost, context: EncodeContext): Int = 2 + value.host.length + BoundaryDispCodec.sizeHint(value.disp, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
