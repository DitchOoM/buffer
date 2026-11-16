package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object Http3FcFrameGoAwayCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrame.GoAway {
    val frameType = Http3FcFrameTypeCodec.decode(buffer, context)
    val __framingOuterLimit = buffer.limit()
    val __framingLength = Http3FcLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "GoAway.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Http3FcLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val id = QuicVarintCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "GoAway.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Http3FcFrame.GoAway(frameType = frameType, id = id)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Http3FcFrame.GoAway,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer {
    val __framingHeaderSize = Http3FcFrameTypeCodec.wireSize(value.frameType, context)
    require(__framingHeaderSize is WireSize.Exact) {
      "framing header codec returned a non-Exact wire size for GoAway.frameType"
    }
    return FramedEncoder.encode(
      factory = factory,
      framingCodec = Http3FcLengthCodec,
      context = context,
      headerWireWidth = __framingHeaderSize.bytes,
      writeHeader = { buffer ->
        Http3FcFrameTypeCodec.encode(buffer, value.frameType, context)
      },
    ) { buffer ->
      QuicVarintCodec.encode(buffer, value.id, context)
    }
  }

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    val __headerFrame = Http3FcFrameTypeCodec.peekFrame(stream, baseOffset)
    if (!__headerFrame.isComplete) {
      return __headerFrame.withoutDeclaredSize()
    }
    val __headerWireWidth = __headerFrame.size
    if (stream.available() - baseOffset < __headerWireWidth + 1) return FramePeek.needsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + __headerWireWidth, Http3FcLengthCodec.maxWireSize) ?: return FramePeek.needsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        Http3FcLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FramePeek.needsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = __headerWireWidth + __framingPrefixWidth + __framingLength.toInt()
      return FramePeek.forDeclaredSize(__total, stream.available() - baseOffset)
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
