package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ProbeRemainingBytesListCodec : Codec<ProbeRemainingBytesList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ProbeRemainingBytesList {
    val xs = mutableListOf<ProbeSealed>()
    while (buffer.position() < buffer.limit()) {
      xs += ProbeSealedCodec.decode(buffer, context)
    }
    return ProbeRemainingBytesList(xs = xs)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ProbeRemainingBytesList,
    context: EncodeContext,
  ) {
    for (__elem in value.xs) {
      ProbeSealedCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: ProbeRemainingBytesList, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
