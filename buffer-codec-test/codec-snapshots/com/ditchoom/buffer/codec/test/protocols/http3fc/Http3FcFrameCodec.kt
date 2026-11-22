package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.ForwardCompatibleFactoryKey
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.FramingSizeException
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.Throwable

public object Http3FcFrameCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FcFrame {
    val discriminatorPosition = buffer.position()
    val __discriminator = Http3FcFrameTypeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.type
    return when (__dispatchValue) {
      0 -> Http3FcFrameDataCodec.decode(buffer, context)
      4 -> Http3FcFrameSettingsCodec.decode(buffer, context)
      5 -> Http3FcFramePushPromiseCodec.decode(buffer, context)
      7 -> Http3FcFrameGoAwayCodec.decode(buffer, context)
      64 -> Http3FcFrameExtensionCodec.decode(buffer, context)
      else -> {
        buffer.position(discriminatorPosition)
        val __fcOpcode = Http3FcFrameTypeCodec.decode(buffer, context).raw
        val __fcLength = Http3FcLengthCodec.decode(buffer, context)
        if (declaredFrameBytes(0, __fcLength.toLong()) > buffer.remaining()) {
          throw DecodeException(
                fieldPath = "Unknown.@ForwardCompatible",
                bufferPosition = buffer.position(),
                expected = "a fully-buffered " + __fcLength + "-byte framed body",
                actual = buffer.remaining().toString() + " bytes available",
              )
        }
        val __fcFrameEnd = buffer.position() + __fcLength.toInt()
        val __fcFactory = context[ForwardCompatibleFactoryKey] ?: BufferFactory.managed()
        val __fcRaw = __fcFactory.allocate(__fcLength.toInt())
        val __fcSavedLimit = buffer.limit()
        buffer.setLimit(__fcFrameEnd)
        __fcRaw.write(buffer)
        buffer.setLimit(__fcSavedLimit)
        __fcRaw.resetForRead()
        Http3FcFrame.Unknown(opcode = __fcOpcode, raw = __fcRaw)
      }
    }
  }

  public fun encode(
    `value`: Http3FcFrame,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = when (value) {
    is Http3FcFrame.Data -> Http3FcFrameDataCodec.encode(value, context, factory)
    is Http3FcFrame.Settings -> Http3FcFrameSettingsCodec.encode(value, context, factory)
    is Http3FcFrame.PushPromise -> Http3FcFramePushPromiseCodec.encode(value, context, factory)
    is Http3FcFrame.GoAway -> Http3FcFrameGoAwayCodec.encode(value, context, factory)
    is Http3FcFrame.Extension -> Http3FcFrameExtensionCodec.encode(value, context, factory)
    is Http3FcFrame.Unknown -> {
      val __fcHeader = Http3FcFrameType(value.opcode)
      val __fcHeaderSize = Http3FcFrameTypeCodec.wireBytes(__fcHeader, context).fold(exact = { if (it > Int.MAX_VALUE) throw FramingSizeException(Http3FcFrameTypeCodec) else it.toInt() }, backPatch = { throw FramingSizeException(Http3FcFrameTypeCodec) })
      FramedEncoder.encode(
        factory = factory,
        framingCodec = Http3FcLengthCodec,
        context = context,
        headerWireWidth = __fcHeaderSize,
        writeHeader = { __fcBuf -> Http3FcFrameTypeCodec.encode(__fcBuf, __fcHeader, context) },
      ) { __fcBuf ->
        __fcBuf.write(value.raw.slice())
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
