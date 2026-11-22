package com.ditchoom.buffer.codec.test.protocols.lengthprefixedusecodec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.LengthPrefixedListEncoder
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object TaggedPropertyBagCodec : Codec<TaggedPropertyBag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TaggedPropertyBag {
    val tag = buffer.readUByte()
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __propertiesLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "TaggedPropertyBag.properties",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __propertiesLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __propertiesLength)
    if (buffer.limit() > __propertiesOuterLimit) {
      val __widenedLimit = buffer.limit()
      buffer.setLimit(__propertiesOuterLimit)
      throw DecodeException(
            fieldPath = "TaggedPropertyBag.properties",
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
    return TaggedPropertyBag(tag = tag, properties = properties)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TaggedPropertyBag,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.tag)
    var __propertiesBodyBytes = 0L
    var __propertiesAllExact = true
    val __propertiesElements = value.properties.iterator()
    while (__propertiesAllExact && __propertiesElements.hasNext()) {
      __propertiesBodyBytes += PropertyEntryCodec.wireBytes(__propertiesElements.next(), context).fold(exact = { it }, backPatch = { __propertiesAllExact = false; 0L })
    }
    if (__propertiesAllExact && __propertiesBodyBytes <= Int.MAX_VALUE) {
      MqttRemainingLengthCodec.encode(buffer, __propertiesBodyBytes.toUInt(), context)
      for (__elem in value.properties) {
        PropertyEntryCodec.encode(buffer, __elem, context)
      }
    } else {
      LengthPrefixedListEncoder.encode(buffer, BufferFactory.Default, MqttRemainingLengthCodec, value.properties, PropertyEntryCodec, context)
    }
  }

  override fun wireBytes(`value`: TaggedPropertyBag, context: EncodeContext): WireByteCount {
    var __propertiesBodyBytes = 0L
    val __propertiesBodyBytesList = value.properties
    for (__i in 0 until __propertiesBodyBytesList.size) {
      __propertiesBodyBytes += PropertyEntryCodec.wireBytes(__propertiesBodyBytesList[__i], context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    if (__propertiesBodyBytes > Int.MAX_VALUE.toLong()) return WireByteCount.BackPatch
    val __propertiesSize = MqttRemainingLengthCodec.encodedWidth(__propertiesBodyBytes) + __propertiesBodyBytes
    return WireByteCount.exact(1L + __propertiesSize)
  }

  override fun sizeHint(`value`: TaggedPropertyBag, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 1).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(1 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
}
