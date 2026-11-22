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
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object PropertyBagFrameCodec : Codec<PropertyBagFrame> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): PropertyBagFrame {
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __propertiesLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "PropertyBagFrame.properties",
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

  override fun wireBytes(`value`: PropertyBagFrame, context: EncodeContext): WireByteCount {
    var __propertiesBodyBytes = 0L
    val __propertiesBodyBytesList = value.properties
    for (__i in 0 until __propertiesBodyBytesList.size) {
      __propertiesBodyBytes += PropertyEntryCodec.wireBytes(__propertiesBodyBytesList[__i], context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    }
    if (__propertiesBodyBytes > Int.MAX_VALUE.toLong()) return WireByteCount.BackPatch
    val __propertiesSize = MqttRemainingLengthCodec.encodedWidth(__propertiesBodyBytes).fold(exact = { it }, backPatch = { MqttRemainingLengthCodec.wireBytes(__propertiesBodyBytes.toUInt(), context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch }) }) + __propertiesBodyBytes
    return WireByteCount.exact(0L + __propertiesSize)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: PropertyBagFrame, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: PropertyBagFrame, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 0).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(0 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
