package com.ditchoom.buffer.codec.test.protocols.versioning

import com.ditchoom.buffer.ByteOrder
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
import com.ditchoom.buffer.swapBytes
import kotlin.Int

public object RegisterEnvelopeCodec : Codec<RegisterEnvelope> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RegisterEnvelope {
    val sequenceRaw = buffer.readInt()
    val sequence = if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) sequenceRaw else swapBytes(sequenceRaw)
    val registerPrefixB0 = buffer.readUByte().toUInt()
    val registerPrefixB1 = buffer.readUByte().toUInt()
    val registerPrefix = ((registerPrefixB0 shl 8) or registerPrefixB1)
    if (registerPrefix > Int.MAX_VALUE.toUInt()) {
      throw DecodeException(fieldPath = "RegisterEnvelope.register", bufferPosition = -1, expected = "length prefix <= ${'$'}{Int.MAX_VALUE}", actual = registerPrefix.toString())
    }
    val registerLength = registerPrefix.toInt()
    if (registerLength > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "RegisterEnvelope.register",
            bufferPosition = buffer.position(),
            expected = "a " + registerLength + "-byte bounded region within the enclosing limit",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    val registerOuterLimit = buffer.limit()
    buffer.setLimit(buffer.position() + registerLength)
    val register = try {
      RegisterV3Codec.decode(buffer, context)
    } finally {
      buffer.setLimit(registerOuterLimit)
    }
    return RegisterEnvelope(sequence = sequence, register = register)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RegisterEnvelope,
    context: EncodeContext,
  ) {
    val sequenceRaw = value.sequence
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) sequenceRaw else swapBytes(sequenceRaw))
    when (val registerWireSize = RegisterV3Codec.wireSize(value.register, context)) {
      is WireSize.Exact -> {
        val registerByteCount = registerWireSize.bytes
        if (registerByteCount > 65_535) {
          throw EncodeException(fieldPath = "RegisterEnvelope.register", reason = """encoded message byte length ${registerByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        val registerPrefix = registerByteCount.toUInt()
        buffer.writeUByte(((registerPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((registerPrefix and 0xFFu).toUByte())
        val registerBodyStart = buffer.position()
        RegisterV3Codec.encode(buffer, value.register, context)
        if (buffer.position() - registerBodyStart != registerByteCount) {
          throw EncodeException(fieldPath = "RegisterEnvelope.register", reason = """wireSize declared ${registerByteCount} bytes but encode wrote ${buffer.position() - registerBodyStart} — the codec's wireSize and encode disagree""")
        }
      }
      WireSize.BackPatch -> {
        val registerSizePosition = buffer.position()
        repeat(2) { buffer.writeUByte(0u) }
        val registerBodyStart = buffer.position()
        RegisterV3Codec.encode(buffer, value.register, context)
        val registerEndPosition = buffer.position()
        val registerPatchByteCount = registerEndPosition - registerBodyStart
        if (registerPatchByteCount > 65_535) {
          throw EncodeException(fieldPath = "RegisterEnvelope.register", reason = """encoded message byte length ${registerPatchByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
        }
        buffer.position(registerSizePosition)
        val registerPatchPrefix = registerPatchByteCount.toUInt()
        buffer.writeUByte(((registerPatchPrefix shr 8) and 0xFFu).toUByte())
        buffer.writeUByte((registerPatchPrefix and 0xFFu).toUByte())
        buffer.position(registerEndPosition)
      }
    }
  }

  override fun wireSize(`value`: RegisterEnvelope, context: EncodeContext): WireSize = when (val registerSize = RegisterV3Codec.wireSize(value.register, context)) {
    is WireSize.Exact -> WireSize.Exact(6 + registerSize.bytes)
    WireSize.BackPatch -> WireSize.BackPatch
  }

  override fun sizeHint(`value`: RegisterEnvelope, context: EncodeContext): Int = 6 + RegisterV3Codec.sizeHint(value.register, context)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 4) return PeekResult.NeedsMoreData
    __offset += 4
    if (stream.available() - baseOffset < __offset + 2) return PeekResult.NeedsMoreData
    val registerPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
    val registerPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
    val registerPrefix = ((registerPrefixB0 shl 8) or registerPrefixB1).toUInt()
    if (registerPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
      throw DecodeException(fieldPath = "RegisterEnvelope.register", bufferPosition = baseOffset + __offset, expected = "__offset + 2 + length prefix <= ${'$'}{Int.MAX_VALUE}", actual = """${__offset + 2 + registerPrefix.toInt()}""")
    }
    __offset += 2 + registerPrefix.toInt()
    return if (stream.available() - baseOffset >= __offset) PeekResult.Complete(__offset) else PeekResult.NeedsMoreData
  }
}
