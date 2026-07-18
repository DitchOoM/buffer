package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object RemainingValueClassIdCodec : Codec<RemainingValueClassId> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): RemainingValueClassId {
    val kind = buffer.readByte()
    val id = SessionId(buffer.readString(buffer.remaining(), Charset.UTF8))
    return RemainingValueClassId(kind = kind, id = id)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: RemainingValueClassId,
    context: EncodeContext,
  ) {
    buffer.writeByte(value.kind)
    buffer.writeString(value.id.value, Charset.UTF8)
  }

  override fun wireSize(`value`: RemainingValueClassId, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
