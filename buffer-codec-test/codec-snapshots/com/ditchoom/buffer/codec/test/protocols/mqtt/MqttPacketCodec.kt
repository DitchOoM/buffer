package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public class MqttPacketCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) {
  private val publishCodec: MqttPacketPublishCodec<P> = MqttPacketPublishCodec(payloadCodec)

  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPacket<P> {
    val discriminatorPosition = buffer.position()
    val __discriminator = MqttFixedHeaderCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.packetType
    return when (__dispatchValue) {
      1 -> MqttPacketConnectCodec.decode(buffer, context)
      2 -> MqttPacketConnAckCodec.decode(buffer, context)
      3 -> publishCodec.decode(buffer, context)
      4 -> MqttPacketPubAckCodec.decode(buffer, context)
      5 -> MqttPacketPubRecCodec.decode(buffer, context)
      6 -> MqttPacketPubRelCodec.decode(buffer, context)
      7 -> MqttPacketPubCompCodec.decode(buffer, context)
      8 -> MqttPacketSubscribeCodec.decode(buffer, context)
      9 -> MqttPacketSubAckCodec.decode(buffer, context)
      10 -> MqttPacketUnsubscribeCodec.decode(buffer, context)
      11 -> MqttPacketUnsubAckCodec.decode(buffer, context)
      12 -> MqttPacketPingReqCodec.decode(buffer, context)
      13 -> MqttPacketPingRespCodec.decode(buffer, context)
      14 -> MqttPacketDisconnectCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "MqttPacket.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}", actual = """${__dispatchValue}""")
      }
    }
  }

  public fun encode(
    `value`: MqttPacket<P>,
    context: EncodeContext,
    factory: BufferFactory,
  ): ReadBuffer {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is MqttPacket.Connect -> MqttPacketConnectCodec.encode(value, context, factory)
      is MqttPacket.ConnAck -> MqttPacketConnAckCodec.encode(value, context, factory)
      is MqttPacket.Publish<*> -> publishCodec.encode(value as MqttPacket.Publish<P>, context, factory)
      is MqttPacket.PubAck -> MqttPacketPubAckCodec.encode(value, context, factory)
      is MqttPacket.PubRec -> MqttPacketPubRecCodec.encode(value, context, factory)
      is MqttPacket.PubRel -> MqttPacketPubRelCodec.encode(value, context, factory)
      is MqttPacket.PubComp -> MqttPacketPubCompCodec.encode(value, context, factory)
      is MqttPacket.Subscribe -> MqttPacketSubscribeCodec.encode(value, context, factory)
      is MqttPacket.SubAck -> MqttPacketSubAckCodec.encode(value, context, factory)
      is MqttPacket.Unsubscribe -> MqttPacketUnsubscribeCodec.encode(value, context, factory)
      is MqttPacket.UnsubAck -> MqttPacketUnsubAckCodec.encode(value, context, factory)
      is MqttPacket.PingReq -> MqttPacketPingReqCodec.encode(value, context, factory)
      is MqttPacket.PingResp -> MqttPacketPingRespCodec.encode(value, context, factory)
      is MqttPacket.Disconnect -> MqttPacketDisconnectCodec.encode(value, context, factory)
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

  public companion object {
    public fun <P : Payload> decodeAggregating(
      buffer: ReadBuffer,
      context: DecodeContext,
      onPublish: (MqttPacketPublishCodec.Partial<P>) -> MqttPacket.Publish<P> = { _ -> throw DecodeException(fieldPath = "MqttPacket.Publish.handler", bufferPosition = -1, expected = "consumer-supplied Publish handler", actual = "no handler supplied") },
    ): MqttPacket<P> {
      val discriminatorPosition = buffer.position()
      val __discriminator = MqttFixedHeaderCodec.decode(buffer, context)
      buffer.position(discriminatorPosition)
      val __dispatchValue = __discriminator.packetType
      return when (__dispatchValue) {
        1 -> MqttPacketConnectCodec.decode(buffer, context)
        2 -> MqttPacketConnAckCodec.decode(buffer, context)
        3 -> onPublish(MqttPacketPublishCodec.partial<P>(buffer, context))
        4 -> MqttPacketPubAckCodec.decode(buffer, context)
        5 -> MqttPacketPubRecCodec.decode(buffer, context)
        6 -> MqttPacketPubRelCodec.decode(buffer, context)
        7 -> MqttPacketPubCompCodec.decode(buffer, context)
        8 -> MqttPacketSubscribeCodec.decode(buffer, context)
        9 -> MqttPacketSubAckCodec.decode(buffer, context)
        10 -> MqttPacketUnsubscribeCodec.decode(buffer, context)
        11 -> MqttPacketUnsubAckCodec.decode(buffer, context)
        12 -> MqttPacketPingReqCodec.decode(buffer, context)
        13 -> MqttPacketPingRespCodec.decode(buffer, context)
        14 -> MqttPacketDisconnectCodec.decode(buffer, context)
        else -> {
          throw DecodeException(fieldPath = "MqttPacket.discriminator", bufferPosition = discriminatorPosition, expected = "one of {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}", actual = """${__dispatchValue}""")
        }
      }
    }
  }
}
