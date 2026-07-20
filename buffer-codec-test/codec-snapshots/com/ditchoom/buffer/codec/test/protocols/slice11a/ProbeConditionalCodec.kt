package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ProbeConditionalCodec : Codec<ProbeConditional> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ProbeConditional {
    val present = buffer.readByte() != 0.toByte()
    val seal: ProbeSealed? = if (present) ProbeSealedDelegateCodec.decode(buffer, context) else null
    return ProbeConditional(present = present, seal = seal)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ProbeConditional,
    context: EncodeContext,
  ) {
    buffer.writeByte(if (value.present) 1.toByte() else 0.toByte())
    if (value.present) {
      val sealValue = value.seal ?: throw EncodeException(fieldPath = "ProbeConditional.seal", reason = "@When(\"present\") predicate is true but field is null")
      ProbeSealedDelegateCodec.encode(buffer, sealValue, context)
    }
  }

  override fun wireSize(`value`: ProbeConditional, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: ProbeConditional, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
