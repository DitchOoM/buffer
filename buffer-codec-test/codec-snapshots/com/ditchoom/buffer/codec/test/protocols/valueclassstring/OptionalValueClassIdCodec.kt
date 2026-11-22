package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FrameTooLargeException
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object OptionalValueClassIdCodec : Codec<OptionalValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): OptionalValueClassId {
    val hasId = buffer.readByte() != 0.toByte()
    val id: UserId? = if (hasId) {
      val idPrefixB0 = buffer.readUByte().toUInt()
      val idPrefixB1 = buffer.readUByte().toUInt()
      val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1)
      if (idPrefix > Int.MAX_VALUE.toUInt()) {
        throw FrameTooLargeException(declaredBytes = idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      val idLength = idPrefix.toInt()
      UserId(buffer.readText(idLength, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY)))
    } else {
      null
    }
    return OptionalValueClassId(hasId = hasId, id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: OptionalValueClassId,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.hasId) 1.toByte() else 0.toByte())
    if (value.hasId) {
      val idValue = value.id ?: throw EncodeException(fieldPath = "OptionalValueClassId.id", reason = "@When(\"hasId\") predicate is true but field is null")
      val idSizePosition = buffer.position()
      repeat(2) { buffer.writeUByte(0u) }
      val idBodyStart = buffer.position()
      buffer.writeText(idValue.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
      val idEndPosition = buffer.position()
      val idByteCount = idEndPosition - idBodyStart
      if (idByteCount > 65_535) {
        throw EncodeException(fieldPath = "OptionalValueClassId.id", reason = """UTF-8 byte length ${idByteCount} exceeds @LengthPrefixed(LengthPrefix.Short) max 65535""")
      }
      buffer.position(idSizePosition)
      val idPrefix = idByteCount.toUInt()
      buffer.writeUByte(((idPrefix shr 8) and 0xFFu).toUByte())
      buffer.writeUByte((idPrefix and 0xFFu).toUByte())
      buffer.position(idEndPosition)
    }
  }

  override fun wireBytes(`value`: OptionalValueClassId, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: OptionalValueClassId, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    var __offset = 0
    if (stream.available() - baseOffset < __offset + 1) return FrameSize.NeedsMoreData
    val hasId = stream.peekByte(baseOffset + __offset) != 0.toByte()
    __offset += 1
    if (hasId) {
      if (stream.available() - baseOffset < __offset + 2) return FrameSize.NeedsMoreData
      val idPrefixB0 = stream.peekByte(baseOffset + __offset).toInt() and 0xFF
      val idPrefixB1 = stream.peekByte(baseOffset + __offset + 1).toInt() and 0xFF
      val idPrefix = ((idPrefixB0 shl 8) or idPrefixB1).toUInt()
      if (idPrefix > (Int.MAX_VALUE - __offset - 2).toUInt()) {
        throw FrameTooLargeException(declaredBytes = __offset.toLong() + 2 + idPrefix.toLong(), maxBytes = Int.MAX_VALUE)
      }
      __offset += 2 + idPrefix.toInt()
    }
    return FrameSize.declared(0, __offset.toLong())
  }
}
