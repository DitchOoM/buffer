package com.ditchoom.buffer.codec.test.protocols.http3fc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.ForwardCompatibleFactoryKey
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object Http3FcFrameCodec {
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
        if (__fcLength.toInt() > buffer.remaining()) {
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
      val __fcHeaderSize = Http3FcFrameTypeCodec.wireSize(__fcHeader, context)
      require(__fcHeaderSize is WireSize.Exact) {
        "discriminator codec returned a non-Exact wire size for the preserved opcode"
      }
      FramedEncoder.encode(
        factory = factory,
        framingCodec = Http3FcLengthCodec,
        context = context,
        headerWireWidth = __fcHeaderSize.bytes,
        writeHeader = { __fcBuf -> Http3FcFrameTypeCodec.encode(__fcBuf, __fcHeader, context) },
      ) { __fcBuf ->
        __fcBuf.write(value.raw.slice())
      }
    }
  }

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    val __headerFrame = Http3FcFrameTypeCodec.peekFrameSize(stream, baseOffset)
    if (__headerFrame !is PeekResult.Complete) {
      return __headerFrame
    }
    val __headerWireWidth = __headerFrame.bytes
    if (stream.available() - baseOffset < __headerWireWidth + 1) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + __headerWireWidth, Http3FcLengthCodec.maxWireSize) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        Http3FcLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = __headerWireWidth + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
