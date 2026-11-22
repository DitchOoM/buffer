package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.FramingSizeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.Throwable

public object Http3FcFrameSettingsCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrame.Settings {
    val frameType = Http3FcFrameTypeCodec.decode(buffer, context)
    val __framingOuterLimit = buffer.limit()
    val __framingLength = Http3FcLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "Settings.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    Http3FcLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val entries = mutableListOf<Http3FcSetting>()
      while (buffer.position() < buffer.limit()) {
        entries += Http3FcSettingCodec.decode(buffer, context)
      }
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "Settings.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      Http3FcFrame.Settings(frameType = frameType, entries = entries)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: Http3FcFrame.Settings,
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
      for (__elem in value.entries) {
        Http3FcSettingCodec.encode(buffer, __elem, context)
      }
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __headerWidth = Http3FcFrameTypeCodec.frameSize(stream, baseOffset + 0).fold(declared = { it }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    if (__headerWidth >= stream.available() - baseOffset) return FrameSize.NeedsMoreData
    val __headerWireWidth = __headerWidth.toInt()
    val __framingPeek = stream.peekBuffer(baseOffset + __headerWireWidth, Http3FcLengthCodec.maxWireSize) ?: return FrameSize.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        Http3FcLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FrameSize.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      return FrameSize.declared(__headerWireWidth + __framingPrefixWidth, __framingLength.toLong())
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
