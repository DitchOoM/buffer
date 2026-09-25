package com.ditchoom.buffer.codec.test.protocols.versioning

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object RegisterV1Codec : Codec<RegisterV1> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RegisterV1 {
    val hostnamePrefixB0 = buffer.readUByte().toUInt()
    val hostnamePrefixB1 = buffer.readUByte().toUInt()
    val hostnamePrefix = ((hostnamePrefixB0 shl 8) or hostnamePrefixB1)
    if (hostnamePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "RegisterV1.hostname", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = hostnamePrefix.toString())
    }
    val hostnameLength = hostnamePrefix.toInt()
    val hostname = buffer.readText(hostnameLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val pidRaw = buffer.readInt()
    val pid = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) pidRaw else swapBytes(pidRaw)
    val secure = buffer.readByte() != 0.toByte()
    return RegisterV1(hostname = hostname, pid = pid, secure = secure)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RegisterV1,
    context: EncodeContext,
  ) {
    val hostnameSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val hostnameBodyStart = buffer.position()
    buffer.writeText(value.hostname, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val hostnameEndPosition = buffer.position()
    val hostnameByteCount = hostnameEndPosition - hostnameBodyStart
    if (hostnameByteCount > 65_535) {
      throw EncodeException(fieldPath = "RegisterV1.hostname", reason = """UTF-8 byte length ${hostnameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(hostnameSizePosition)
    val hostnamePrefix = hostnameByteCount.toUInt()
    buffer.writeUByte(((hostnamePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((hostnamePrefix and 0xFFu).toUByte())
    buffer.position(hostnameEndPosition)
    val pidRaw = value.pid
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) pidRaw else swapBytes(pidRaw))
    buffer.writeByte(if (value.secure) 1.toByte() else 0.toByte())
  }

  override fun wireSize(`value`: RegisterV1, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: RegisterV1, context: EncodeContext): Int = 7 + value.hostname.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val hostnamePrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val hostnamePrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val hostnamePrefix = ((hostnamePrefixB0 shl 8) or hostnamePrefixB1).toUInt()
    if (hostnamePrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "RegisterV1.hostname", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + hostnamePrefix.toInt()}""")
    }
    __offset += 2 + hostnamePrefix.toInt()
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
