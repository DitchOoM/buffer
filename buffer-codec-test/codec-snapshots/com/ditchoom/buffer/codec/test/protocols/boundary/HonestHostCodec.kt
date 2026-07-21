package com.ditchoom.buffer.codec.test.protocols.boundary

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

public object HonestHostCodec : Codec<HonestHost> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): HonestHost {
    val kind = buffer.readByte()
    val innerPrefixB0 = buffer.readUByte().toUInt()
    val innerPrefixB1 = buffer.readUByte().toUInt()
    val innerPrefix = ((innerPrefixB0 shl 8) or innerPrefixB1)
    if (innerPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "HonestHost.inner", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = innerPrefix.toString())
    }
    val innerLength = innerPrefix.toInt()
    val innerOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + innerLength)
    val inner = try {
      HonestInnerCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(innerOuterLimit)
    }
    return HonestHost(kind = kind, inner = inner)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: HonestHost,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    when (val innerWireSize = HonestInnerCodec.wireSize(value.inner, context)) {
      is WireSize.Exact -> {
        val innerByteCount = innerWireSize.bytes
        if (innerByteCount > 65_535) {
          throw EncodeException(fieldPath = "HonestHost.inner", reason = """encoded message byte length ${innerByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val innerPrefix = innerByteCount.toUInt()
        buffer.writeUByte(((innerPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((innerPrefix and 0xFFu).toUByte())
        val innerBodyStart = buffer.position()
        HonestInnerCodec.encode(buffer, value.inner, context)
        if (buffer.position() - innerBodyStart != innerByteCount) {
          throw EncodeException(fieldPath = "HonestHost.inner", reason = """wireSize declared ${innerByteCount} bytes but encode wrote ${buffer.position() - innerBodyStart} — the codec's wireSize and encode disagree""")
        }
      }
      WireSize.BackPatch -> {
        val innerSizePosition = buffer.position()
        repeat(2) { buffer.writeUByte(0u) }
        val innerBodyStart = buffer.position()
        HonestInnerCodec.encode(buffer, value.inner, context)
        val innerEndPosition = buffer.position()
        val innerPatchByteCount = innerEndPosition - innerBodyStart
        if (innerPatchByteCount > 65_535) {
          throw EncodeException(fieldPath = "HonestHost.inner", reason = """encoded message byte length ${innerPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        buffer.position(innerSizePosition)
        val innerPatchPrefix = innerPatchByteCount.toUInt()
        buffer.writeUByte(((innerPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((innerPatchPrefix and 0xFFu).toUByte())
        buffer.position(innerEndPosition)
      }
    }
  }

  override fun wireSize(`value`: HonestHost, context: EncodeContext): WireSize = when (val innerSize = HonestInnerCodec.wireSize(value.inner, context)) {
    is WireSize.Exact -> WireSize.Exact(3 + innerSize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: HonestHost, context: EncodeContext): Int = 3 + HonestInnerCodec.sizeHint(value.inner, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return PeekResult.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val innerPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val innerPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val innerPrefix = ((innerPrefixB0 shl 8) or innerPrefixB1).toUInt()
    if (innerPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "HonestHost.inner", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + innerPrefix.toInt()}""")
    }
    __offset += 2 + innerPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
