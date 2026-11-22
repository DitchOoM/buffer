package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public class MqttV5PacketCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : FrameDetector {
  private val publishCodec: MqttV5PacketPublishCodec<P> = MqttV5PacketPublishCodec(payloadCodec)

  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttV5Packet<P> {
    val discriminatorPosition = buffer.position()
    val __discriminator = MqttFixedHeaderCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.packetType
    return when (__dispatchValue) {
      1 -> MqttV5PacketConnectCodec.decode(buffer, context)
      2 -> MqttV5PacketConnAckCodec.decode(buffer, context)
      3 -> publishCodec.decode(buffer, context)
      4 -> MqttV5PacketPubAckCodec.decode(buffer, context)
      5 -> MqttV5PacketPubRecCodec.decode(buffer, context)
      6 -> MqttV5PacketPubRelCodec.decode(buffer, context)
      7 -> MqttV5PacketPubCompCodec.decode(buffer, context)
      8 -> MqttV5PacketSubscribeCodec.decode(buffer, context)
      9 -> MqttV5PacketSubAckCodec.decode(buffer, context)
      10 -> MqttV5PacketUnsubscribeCodec.decode(buffer, context)
      11 -> MqttV5PacketUnsubAckCodec.decode(buffer, context)
      12 -> MqttV5PacketPingReqCodec.decode(buffer, context)
      13 -> MqttV5PacketPingRespCodec.decode(buffer, context)
      14 -> MqttV5PacketDisconnectCodec.decode(buffer, context)
      15 -> MqttV5PacketAuthCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "MqttV5Packet.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}", actual = """${__dispatchValue}""")
      }
    }
  }

  public fun encode(
    `value`: MqttV5Packet<P>,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is MqttV5Packet.Connect -> MqttV5PacketConnectCodec.encode(value, context, factory)
      is MqttV5Packet.ConnAck -> MqttV5PacketConnAckCodec.encode(value, context, factory)
      is MqttV5Packet.Publish<*> -> publishCodec.encode(value as MqttV5Packet.Publish<P>, context, factory)
      is MqttV5Packet.PubAck -> MqttV5PacketPubAckCodec.encode(value, context, factory)
      is MqttV5Packet.PubRec -> MqttV5PacketPubRecCodec.encode(value, context, factory)
      is MqttV5Packet.PubRel -> MqttV5PacketPubRelCodec.encode(value, context, factory)
      is MqttV5Packet.PubComp -> MqttV5PacketPubCompCodec.encode(value, context, factory)
      is MqttV5Packet.Subscribe -> MqttV5PacketSubscribeCodec.encode(value, context, factory)
      is MqttV5Packet.SubAck -> MqttV5PacketSubAckCodec.encode(value, context, factory)
      is MqttV5Packet.Unsubscribe -> MqttV5PacketUnsubscribeCodec.encode(value, context, factory)
      is MqttV5Packet.UnsubAck -> MqttV5PacketUnsubAckCodec.encode(value, context, factory)
      is MqttV5Packet.PingReq -> MqttV5PacketPingReqCodec.encode(value, context, factory)
      is MqttV5Packet.PingResp -> MqttV5PacketPingRespCodec.encode(value, context, factory)
      is MqttV5Packet.Disconnect -> MqttV5PacketDisconnectCodec.encode(value, context, factory)
      is MqttV5Packet.Auth -> MqttV5PacketAuthCodec.encode(value, context, factory)
    }
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Companion.frameSize(stream, baseOffset)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)

  public companion object : FrameDetector {
    public fun <P : Payload> decodeAggregating(
      buffer: ReadBuffer,
      context: DecodeContext,
      onPublish: (MqttV5PacketPublishCodec.Partial<P>) -> MqttV5Packet.Publish<P> = { _ -> throw DecodeException(fieldPath = "MqttV5Packet.Publish.handler", bufferPosition = -1, expected = "consumer-supplied Publish handler", actual = "no handler supplied") },
    ): MqttV5Packet<P> {
      val discriminatorPosition = buffer.position()
      val __discriminator = MqttFixedHeaderCodec.decode(buffer, context)
      buffer.position(discriminatorPosition)
      val __dispatchValue = __discriminator.packetType
      return when (__dispatchValue) {
        1 -> MqttV5PacketConnectCodec.decode(buffer, context)
        2 -> MqttV5PacketConnAckCodec.decode(buffer, context)
        3 -> onPublish(MqttV5PacketPublishCodec.partial<P>(buffer, context))
        4 -> MqttV5PacketPubAckCodec.decode(buffer, context)
        5 -> MqttV5PacketPubRecCodec.decode(buffer, context)
        6 -> MqttV5PacketPubRelCodec.decode(buffer, context)
        7 -> MqttV5PacketPubCompCodec.decode(buffer, context)
        8 -> MqttV5PacketSubscribeCodec.decode(buffer, context)
        9 -> MqttV5PacketSubAckCodec.decode(buffer, context)
        10 -> MqttV5PacketUnsubscribeCodec.decode(buffer, context)
        11 -> MqttV5PacketUnsubAckCodec.decode(buffer, context)
        12 -> MqttV5PacketPingReqCodec.decode(buffer, context)
        13 -> MqttV5PacketPingRespCodec.decode(buffer, context)
        14 -> MqttV5PacketDisconnectCodec.decode(buffer, context)
        15 -> MqttV5PacketAuthCodec.decode(buffer, context)
        else -> {
          throw DecodeException(fieldPath = "MqttV5Packet.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}", actual = """${__dispatchValue}""")
        }
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
}
