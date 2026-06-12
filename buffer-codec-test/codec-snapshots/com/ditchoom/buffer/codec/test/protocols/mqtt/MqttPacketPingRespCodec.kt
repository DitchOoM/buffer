package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramedEncoder
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.Throwable

public object MqttPacketPingRespCodec {
  public fun decode(buffer: ReadBuffer, context: DecodeContext): MqttPacket.PingResp {
    val header = MqttFixedHeader(buffer.readUByte())
    val __framingOuterLimit = buffer.limit()
    val __framingLength = MqttRemainingLengthCodec.decode(buffer, context)
    if (__framingLength.toInt() > buffer.remaining()) {
      throw DecodeException(
            fieldPath = "PingResp.@FramedBy",
            bufferPosition = buffer.position(),
            expected = "a fully-buffered " + __framingLength + "-byte framed body",
            actual = buffer.remaining().toString() + " bytes available",
          )
    }
    MqttRemainingLengthCodec.applyBound(buffer, __framingLength)
    val __framingStart = buffer.position()
    val __framingBound = __framingStart + __framingLength.toInt()
    return try {
      if (buffer.position() != __framingBound) {
        throw DecodeException(
              fieldPath = "PingResp.@FramedBy",
              bufferPosition = buffer.position(),
              expected = "body to consume " + __framingLength + " bytes",
              actual = (buffer.position() - __framingStart).toString() + " bytes",
            )
      }
      MqttPacket.PingResp(header = header)
    } finally {
      buffer.setLimit(__framingOuterLimit)
    }
  }

  public fun encode(
    `value`: MqttPacket.PingResp,
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
