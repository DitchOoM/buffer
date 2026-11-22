package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object MisdeclaredHostCodec : Codec<MisdeclaredHost> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): MisdeclaredHost {
    val kind = buffer.readByte()
    val innerPrefixB0 = buffer.readUByte().toUInt()
    val innerPrefixB1 = buffer.readUByte().toUInt()
    val innerPrefix = ((innerPrefixB0 shl 8) or innerPrefixB1)
    if (innerPrefix > Int.MAX_VALUE.toUInt()) {
      throw FrameTooLargeException(declaredBytes = innerPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    val innerLength = innerPrefix.toInt()
    if (innerLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "MisdeclaredHost.inner",
            bufferPosition = buffer.position(),
            expected = "a " + innerLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    val innerOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + innerLength)
    val inner = try {
      MisdeclaredInnerCodec.decode(buffer, context)
    } finally {
      buffer.setLimit(innerOuterLimit)
    }
    return MisdeclaredHost(kind = kind, inner = inner)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: MisdeclaredHost,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    MisdeclaredInnerCodec.wireBytes(value.inner, context).fold(
      exact = { innerWireBytes ->
        if (innerWireBytes > Int.MAX_VALUE) {
          throw EncodeException(fieldPath = "MisdeclaredHost.inner", reason = """encoded message byte length ${innerWireBytes} exceeds Int.MAX_VALUE""")
        }
        val innerByteCount = innerWireBytes.toInt()
        if (innerByteCount > 65_535) {
          throw EncodeException(fieldPath = "MisdeclaredHost.inner", reason = """encoded message byte length ${innerByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val innerPrefix = innerByteCount.toUInt()
        buffer.writeUByte(((innerPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((innerPrefix and 0xFFu).toUByte())
        val innerBodyStart = buffer.position()
        MisdeclaredInnerCodec.encode(buffer, value.inner, context)
        if (buffer.position() - innerBodyStart != innerByteCount) {
          throw EncodeException(fieldPath = "MisdeclaredHost.inner", reason = """wireBytes declared ${innerByteCount} bytes but encode wrote ${buffer.position() - innerBodyStart} — the codec's wireBytes and encode disagree""")
        }
      },
      backPatch = {
        val innerSizePosition = buffer.position()
        repeat(2) { buffer.writeUByte(0u) }
        val innerBodyStart = buffer.position()
        MisdeclaredInnerCodec.encode(buffer, value.inner, context)
        val innerEndPosition = buffer.position()
        val innerPatchByteCount = innerEndPosition - innerBodyStart
        if (innerPatchByteCount > 65_535) {
          throw EncodeException(fieldPath = "MisdeclaredHost.inner", reason = """encoded message byte length ${innerPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        buffer.position(innerSizePosition)
        val innerPatchPrefix = innerPatchByteCount.toUInt()
        buffer.writeUByte(((innerPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((innerPatchPrefix and 0xFFu).toUByte())
        buffer.position(innerEndPosition)
      },
    )
  }

  override fun wireBytes(`value`: MisdeclaredHost, context: EncodeContext): WireByteCount {
    val __innerSize = MisdeclaredInnerCodec.wireBytes(value.inner, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(3L + __innerSize)
  }

  override fun sizeHint(`value`: MisdeclaredHost, context: EncodeContext): Int = 3 + MisdeclaredInnerCodec.sizeHint(value.inner, context)

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    __offset += 1
    if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
    val innerPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val innerPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val innerPrefix = ((innerPrefixB0 shl 8) or innerPrefixB1).toUInt()
    if (innerPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + innerPrefix.toLong(), maxBytes = Int.MAX_VALUE)
    }
    __offset += 2 + innerPrefix.toInt()
    return FrameSize.declared(0, __offset.toLong())
  }
}
