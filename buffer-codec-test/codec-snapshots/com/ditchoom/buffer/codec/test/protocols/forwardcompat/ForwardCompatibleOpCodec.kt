package com.ditchoom.buffer.codec.test.protocols.forwardcompat

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.ForwardCompatibleFactoryKey
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object ForwardCompatibleOpCodec {
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

  public fun peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      val __total = 1 + __framingPrefixWidth + __framingLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__framingPeek as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
