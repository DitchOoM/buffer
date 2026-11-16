package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http3FcFrameTypeCodec : Codec<Http3FcFrameType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrameType {
    val raw = QuicVarintCodec.decode(buffer, context)
    return Http3FcFrameType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3FcFrameType,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.raw, context)
  }

  override fun wireSize(`value`: Http3FcFrameType, context: EncodeContext): WireSize {
    val __rawSize = when (val __s = QuicVarintCodec.wireSize(value.raw, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __rawSize)
  }

  override fun sizeHint(`value`: Http3FcFrameType, context: EncodeContext): Int = 0

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    val __rawFrame = QuicVarintCodec.peekFrame(stream, baseOffset + 0)
    if (!__rawFrame.isComplete) {
      return __rawFrame.withoutDeclaredSize()
    }
    val __total = 0 + __rawFrame.size + 0
    return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
