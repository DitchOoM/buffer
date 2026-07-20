package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.LengthPrefixedListEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object TaggedPropertyBagCodec : Codec<TaggedPropertyBag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TaggedPropertyBag {
    val tag = buffer.readUByte()
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    MqttRemainingLengthCodec.applyBound(buffer, __propertiesLength)
    val properties = mutableListOf<PropertyEntry>()
    try {
      while (buffer.position() < buffer.limit()) {
        properties += PropertyEntryCodec.decode(buffer, context)
      }
    } finally {
      buffer.setLimit(__propertiesOuterLimit)
    }
    return TaggedPropertyBag(tag = tag, properties = properties)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TaggedPropertyBag,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.tag)
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

  override fun wireSize(`value`: TaggedPropertyBag, context: EncodeContext): WireSize {
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
    return WireSize.Exact(1 + __propertiesSize)
  }

  override fun sizeHint(`value`: TaggedPropertyBag, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
    val __propertiesPeekView = stream.peekBuffer(baseOffset + 1, 5) ?: return PeekResult.NeedsMoreData
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
      val __total = 1 + __propertiesWidth + __propertiesLength.toInt()
      return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
    } finally {
      (__propertiesPeekView as? PlatformBuffer)?.freeNativeMemory()
    }
  }
}
