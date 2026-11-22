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

public object StringTaggedPropertyBagCodec : Codec<StringTaggedPropertyBag> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): StringTaggedPropertyBag {
    val __propertiesOuterLimit = buffer.limit()
    val __propertiesLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __propertiesLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "StringTaggedPropertyBag.properties",
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
            fieldPath = "StringTaggedPropertyBag.properties",
            bufferPosition = buffer.position(),
            expected = "applyBound to narrow within the enclosing limit " + __propertiesOuterLimit,
            actual = "limit " + __widenedLimit,
          )
    }
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
    LengthPrefixedListEncoder.encode(buffer, BufferFactory.Default, MqttRemainingLengthCodec, value.properties, StringTaggedPropertyCodec, context)
  }

  override fun wireBytes(`value`: StringTaggedPropertyBag, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: StringTaggedPropertyBag, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: StringTaggedPropertyBag, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = MqttRemainingLengthCodec.peekLength(stream, baseOffset + 0).fold(declared = { __lengthWidth, __bodyBytes -> FrameSize.declared(0 + __lengthWidth, __bodyBytes) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
