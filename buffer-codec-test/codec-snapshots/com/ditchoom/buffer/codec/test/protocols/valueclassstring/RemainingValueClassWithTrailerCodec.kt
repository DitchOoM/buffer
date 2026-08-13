package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RemainingValueClassWithTrailerCodec : Codec<RemainingValueClassWithTrailer> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemainingValueClassWithTrailer {
    val kind = buffer.readByte()
    val id = SessionId(buffer.readText(buffer.remaining() - 4, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY)))
    val crc = buffer.readUInt()
    return RemainingValueClassWithTrailer(kind = kind, id = id, crc = crc)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemainingValueClassWithTrailer,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    buffer.writeText(value.id.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
    buffer.writeUInt(value.crc)
  }

  override fun wireSize(`value`: RemainingValueClassWithTrailer, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: RemainingValueClassWithTrailer, context: EncodeContext): Int = 5 + value.id.value.length

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
