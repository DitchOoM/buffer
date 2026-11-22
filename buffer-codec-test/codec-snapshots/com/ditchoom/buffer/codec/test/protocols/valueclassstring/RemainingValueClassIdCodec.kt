package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DEFAULT_TEXT_POLICY
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.TextPolicyKey
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RemainingValueClassIdCodec : Codec<RemainingValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemainingValueClassId {
    val kind = buffer.readByte()
    val id = SessionId(buffer.readText(buffer.remaining(), (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY)))
    return RemainingValueClassId(kind = kind, id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemainingValueClassId,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    buffer.writeText(value.id.value, (context[TextPolicyKey] ?: DEFAULT_TEXT_POLICY))
  }

  override fun wireBytes(`value`: RemainingValueClassId, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: RemainingValueClassId, context: EncodeContext): Int = 1 + value.id.value.length

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
