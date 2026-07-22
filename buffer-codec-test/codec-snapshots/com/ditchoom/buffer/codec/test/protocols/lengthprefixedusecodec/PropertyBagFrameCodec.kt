package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.LengthPrefixedListEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object PropertyBagFrameCodec : Codec<PropertyBagFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PropertyBagFrame {
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    MqttRemainingLengthCodec.applyBound(buffer, __propertiesLength)
    if (buffer.limit() > __propertiesOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__propertiesOuterLimit)
      throw DecodeException(
            fieldPath = "PropertyBagFrame.properties",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __propertiesOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
    val properties = mutableListOf<PropertyEntry>()
    try {
      while (buffer.position() < buffer.limit()) {
        properties += PropertyEntryCodec.decode(buffer, context)
      }
    } finally {
      buffer.setLimit(__propertiesOuterLimit)
    }
    return PropertyBagFrame(properties = properties)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: PropertyBagFrame,
    context: EncodeContext,
  ) {
    var __propertiesBodyBytes = 0
    var __propertiesAllExact = true
    for (__elem in value.properties) {
      val __elemSize = PropertyEntryCodec.wireSize(__elem, context)
      if (__elemSize !is WireSize.Exact) {
        __propertiesAllExact = false
        break
      }
      __propertiesBodyBytes += __elemSize.bytes
    }
    if (__propertiesAllExact) {
      MqttRemainingLengthCodec.encode(buffer, __propertiesBodyBytes.toUInt(), context)
      for (__elem in value.properties) {
        PropertyEntryCodec.encode(buffer, __elem, context)
      }
    } else {
      LengthPrefixedListEncoder.encode(buffer, BufferFactory.Default, MqttRemainingLengthCodec, value.properties, PropertyEntryCodec, context)
    }
  }

  override fun wireSize(`value`: PropertyBagFrame, context: EncodeContext): WireSize {
    var __propertiesBodyBytes = 0
    for (__elem in value.properties) {
      when (val __elemSize = PropertyEntryCodec.wireSize(__elem, context)) {
        is WireSize.Exact -> __propertiesBodyBytes += __elemSize.bytes
        WireSize.BackPatch -> return WireSize.BackPatch
      }
    }
    val __propertiesSize = when (val __p = MqttRemainingLengthCodec.wireSize(__propertiesBodyBytes.toUInt(), context)) {
      is WireSize.Exact -> __p.bytes + __propertiesBodyBytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __propertiesSize)
  }

  override fun sizeHint(`value`: PropertyBagFrame, context: EncodeContext): Int = 0

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
