package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.use
import kotlin.Int
import kotlin.Throwable

public object StringTaggedPropertyBagCodec : Codec<StringTaggedPropertyBag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): StringTaggedPropertyBag {
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    MqttRemainingLengthCodec.applyBound(buffer, __propertiesLength)
    val properties = mutableListOf<StringTaggedProperty>()
    try {
      while (buffer.position() < buffer.limit()) {
        properties += StringTaggedPropertyCodec.decode(buffer, context)
      }
    } finally {
      buffer.setLimit(__propertiesOuterLimit)
    }
    return StringTaggedPropertyBag(properties = properties)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: StringTaggedPropertyBag,
    context: EncodeContext,
  ) {
    BufferFactory.Default.allocate(64, buffer.byteOrder).use { __propertiesScratch ->
      for (__elem in value.properties) {
        StringTaggedPropertyCodec.encode(__propertiesScratch, __elem, context)
      }
      val __propertiesBodyBytes = __propertiesScratch.position()
      MqttRemainingLengthCodec.encode(buffer, __propertiesBodyBytes.toUInt(), context)
      __propertiesScratch.resetForRead()
      buffer.write(__propertiesScratch)
    }
  }

  override fun wireSize(`value`: StringTaggedPropertyBag, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 1) return PeekResult.NeedsMoreData
    val __propertiesPeekView = stream.peekBuffer(baseOffset + 0, 5) ?: return PeekResult.NeedsMoreData
    try {
      val __propertiesPriorPos = __propertiesPeekView.position()
      val __propertiesLength = try {
        MqttRemainingLengthCodec.decode(__propertiesPeekView, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return PeekResult.NeedsMoreData
          else -> throw __e
        }
      }
      val __propertiesWidth = __propertiesPeekView.position() - __propertiesPriorPos
      val __total = 0 + __propertiesWidth + __propertiesLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__propertiesPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
