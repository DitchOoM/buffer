package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: ProbeRemainingBytesList, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: ProbeRemainingBytesList, context: EncodeContext): Int {
    var __hint = 0
    for (__elem in value.xs) {
      __hint += ProbeSealedCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
