package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.ForwardCompatibleFactoryKey
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object ForwardCompatibleOpCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): ForwardCompatibleOp {
    val discriminatorPosition = buffer.position()
    val __discriminator = OpCodeCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.code
    return when (__dispatchValue) {
      18 -> ForwardCompatibleOpScrollCodec.decode(buffer, context)
      52 -> ForwardCompatibleOpSetTitleCodec.decode(buffer, context)
      else -> {
        buffer.position(discriminatorPosition)
        val __fcOpcode = buffer.readUByte().toInt()
        val __fcLength = MqttRemainingLengthCodec.decode(buffer, context)
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
        ForwardCompatibleOp.Unknown(opcode = __fcOpcode, raw = __fcRaw)
      }
    }
  }

  public fun encode(
    `value`: ForwardCompatibleOp,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = when (value) {
    is ForwardCompatibleOp.Scroll -> ForwardCompatibleOpScrollCodec.encode(value, context, factory)
    is ForwardCompatibleOp.SetTitle -> ForwardCompatibleOpSetTitleCodec.encode(value, context, factory)
    is ForwardCompatibleOp.Unknown -> FramedEncoder.encode(
      factory = factory,
      framingCodec = MqttRemainingLengthCodec,
      context = context,
      headerWireWidth = 1,
      writeHeader = { __fcBuf -> __fcBuf.writeUByte(value.opcode.toUByte()) },
    ) { __fcBuf ->
      __fcBuf.write(value.raw.slice())
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
