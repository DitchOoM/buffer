package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: ProbeRemainingBytesList, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: ProbeRemainingBytesList, context: EncodeContext): Int {
    var __hint = 0
    for (__elem in value.xs) {
      __hint += ProbeSealedCodec.sizeHint(__elem, context)
    }
    return __hint
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
