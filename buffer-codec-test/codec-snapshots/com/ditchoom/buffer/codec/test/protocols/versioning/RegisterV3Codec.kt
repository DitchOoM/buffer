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
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object RegisterV3Codec : Codec<RegisterV3> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RegisterV3 {
    val hostnamePrefixB0 = buffer.readUByte().toUInt()
    val hostnamePrefixB1 = buffer.readUByte().toUInt()
    val hostnamePrefix = ((hostnamePrefixB0 shl 8) or hostnamePrefixB1)
    if (hostnamePrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "RegisterV3.hostname", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = hostnamePrefix.toString())
    }
    val hostnameLength = hostnamePrefix.toInt()
    val hostname = buffer.readText(hostnameLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val pidRaw = buffer.readInt()
    val pid = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) pidRaw else swapBytes(pidRaw)
    val secure = buffer.readByte() != 0.toByte()
    return if (buffer.remaining() < 4) {
      RegisterV3(hostname = hostname, pid = pid, secure = secure)
    } else {
      val retriesRaw = buffer.readInt()
      val retries = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) retriesRaw else swapBytes(retriesRaw)
      if (buffer.remaining() < 4) {
        RegisterV3(hostname = hostname, pid = pid, secure = secure, retries = retries)
      } else {
        val buildRaw = buffer.readInt()
        val build = BuildNumber(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) buildRaw else swapBytes(buildRaw))
        if (buffer.remaining() < 1) {
          RegisterV3(hostname = hostname, pid = pid, secure = secure, retries = retries, build = build)
        } else {
          val __modeOrdinal = UnsignedVarIntCodec.decode(buffer, context).toInt()
          val mode = RegisterMode.entries.getOrElse(__modeOrdinal) { throw DecodeException(fieldPath = "RegisterV3.mode", bufferPosition = buffer.position(), expected = "an ordinal in 0 until 2", actual = __modeOrdinal.toString()) }
          RegisterV3(hostname = hostname, pid = pid, secure = secure, retries = retries, build = build, mode = mode)
        }
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RegisterV3,
    context: EncodeContext,
  ) {
    val hostnameSizePosition = buffer.position()
    repeat(2) { buffer.writeUByte(0u) }
    val hostnameBodyStart = buffer.position()
    buffer.writeText(value.hostname, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    val hostnameEndPosition = buffer.position()
    val hostnameByteCount = hostnameEndPosition - hostnameBodyStart
    if (hostnameByteCount > 65_535) {
      throw EncodeException(fieldPath = "RegisterV3.hostname", reason = """UTF-8 byte length ${hostnameByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
    }
    buffer.position(hostnameSizePosition)
    val hostnamePrefix = hostnameByteCount.toUInt()
    buffer.writeUByte(((hostnamePrefix shr 8) and 0xFFu).toUByte())
    buffer.writeUByte((hostnamePrefix and 0xFFu).toUByte())
    buffer.position(hostnameEndPosition)
    val pidRaw = value.pid
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) pidRaw else swapBytes(pidRaw))
    buffer.writeByte(if (value.secure) 1.toByte() else 0.toByte())
    val __batch1 = ((value.retries.toLong() and 0xFFFFFFFFL) shl 32) or (value.build.value.toLong() and 0xFFFFFFFFL)
    buffer.writeLong(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) __batch1 else swapBytes(__batch1))
    UnsignedVarIntCodec.encode(buffer, value.mode.ordinal.toUInt(), context)
  }

  override fun wireSize(`value`: RegisterV3, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: RegisterV3, context: EncodeContext): Int = 16 + value.hostname.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
