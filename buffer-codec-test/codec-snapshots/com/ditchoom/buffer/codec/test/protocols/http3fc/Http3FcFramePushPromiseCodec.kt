package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.FramingSizeException
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http3FcFramePushPromiseCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrame.PushPromise {
    val frameType = Http3FcFrameTypeCodec.decode(buffer, context)
    val __framingOuterLimit = buffer.limit()
    val __framingLength = Http3FcLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "PushPromise.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Http3FcLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val pushId = QuicVarintCodec.decode(buffer, context)
      val encodedFieldSection = RawBytesCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "PushPromise.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Http3FcFrame.PushPromise(frameType = frameType, pushId = pushId, encodedFieldSection = encodedFieldSection)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Http3FcFrame.PushPromise,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer {
    val __framingHeaderSize = Http3FcFrameTypeCodec.wireBytes(value.frameType, context).fold(exact = { if (it > Int.MAX_VALUE) throw FramingSizeException(Http3FcFrameTypeCodec) else it.toInt() }, backPatch = { throw FramingSizeException(Http3FcFrameTypeCodec) })
    return FramedEncoder.encode(
      factory = factory,
      framingCodec = Http3FcLengthCodec,
      context = context,
      headerWireWidth = __framingHeaderSize,
      writeHeader = { buffer ->
        Http3FcFrameTypeCodec.encode(buffer, value.frameType, context)
      },
    ) { buffer ->
      QuicVarintCodec.encode(buffer, value.pushId, context)
      RawBytesCodec.encode(buffer, value.encodedFieldSection, context)
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __headerWidth = Http3FcFrameTypeCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    if (__headerWidth > stream.available() - baseOffset - 0) return FrameSize.NeedsMoreData
    val __headerWireWidth = __headerWidth.toInt()
    return Http3FcLengthCodec.peekLength(stream, baseOffset + __headerWireWidth).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(__headerWireWidth + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
  }
}
