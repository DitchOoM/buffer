package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.declaredFrameBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.mqttv5.connack.V5ConnectReasonCodeCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress
import kotlin.Throwable

public object MqttV5PacketConnAckCodec : FrameDetector {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet.ConnAck {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (declaredFrameBytes(0, __framingLength.toLong()) > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "ConnAck.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      val connectAckFlags = buffer.readUByte()
      val reasonCode = V5ConnectReasonCodeCodec.decode(buffer, context)
      val properties = V5PropertyBagCodec.decode(buffer, context)
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "ConnAck.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttV5Packet.ConnAck(header = header, connectAckFlags = connectAckFlags, reasonCode = reasonCode, properties = properties)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttV5Packet.ConnAck,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer = FramedEncoder.encode(
    factory = factory,
    framingCodec = MqttRemainingLengthCodec,
    context = context,
    headerWireWidth = 1,
    writeHeader = { buffer ->
      buffer.writeUByte(value.header.raw)
    },
  ) { buffer ->
    buffer.writeUByte(value.connectAckFlags)
    V5ConnectReasonCodeCodec.encode(buffer, value.reasonCode, context)
    V5PropertyBagCodec.encode(buffer, value.properties, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 2) return FrameSize.NeedsMoreData
    val __framingPeek = stream.peekBuffer(baseOffset + 1, 5) ?: return FrameSize.NeedsMoreData
    try {
      val __framingPeekStart = __framingPeek.position()
      val __framingLength = try {
        MqttRemainingLengthCodec.decode(__framingPeek, DecodeContext.Empty)
      } catch (__e: Throwable) {
        when (__e::class.simpleName) {
          "BufferUnderflowException", "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException" -> return FrameSize.NeedsMoreData
          else -> throw __e
        }
      }
      val __framingPrefixWidth = __framingPeek.position() - __framingPeekStart
      return FrameSize.declared(1 + __framingPrefixWidth, __framingLength.toLong())
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
