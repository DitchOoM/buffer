package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public class WsFrameCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<WsFrame<P>> {
  private val continuationCodec: WsFrameContinuationCodec<P> =
      WsFrameContinuationCodec(payloadCodec)

  private val textCodec: WsFrameTextCodec<P> = WsFrameTextCodec(payloadCodec)

  private val binaryCodec: WsFrameBinaryCodec<P> = WsFrameBinaryCodec(payloadCodec)

  private val pingCodec: WsFramePingCodec<P> = WsFramePingCodec(payloadCodec)

  private val pongCodec: WsFramePongCodec<P> = WsFramePongCodec(payloadCodec)

  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsFrame<P> {
    val discriminatorPosition = buffer.position()
    val __discriminator = FrameHeaderByte1Codec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.opcode
    return when (__dispatchValue) {
      0 -> continuationCodec.decode(buffer, context)
      1 -> textCodec.decode(buffer, context)
      2 -> binaryCodec.decode(buffer, context)
      8 -> WsFrameCloseCodec.decode(buffer, context)
      9 -> pingCodec.decode(buffer, context)
      10 -> pongCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "WsFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1, 2, 8, 9, 10}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsFrame<P>,
    context: EncodeContext,
  ) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
      is WsFrame.Continuation<*> -> continuationCodec.encode(buffer, value as WsFrame.Continuation<P>, context)
      is WsFrame.Text<*> -> textCodec.encode(buffer, value as WsFrame.Text<P>, context)
      is WsFrame.Binary<*> -> binaryCodec.encode(buffer, value as WsFrame.Binary<P>, context)
      is WsFrame.Close -> WsFrameCloseCodec.encode(buffer, value, context)
      is WsFrame.Ping<*> -> pingCodec.encode(buffer, value as WsFrame.Ping<P>, context)
      is WsFrame.Pong<*> -> pongCodec.encode(buffer, value as WsFrame.Pong<P>, context)
    }
  }

  override fun wireSize(`value`: WsFrame<P>, context: EncodeContext): WireSize {
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is WsFrame.Continuation<*> -> continuationCodec.wireSize(value as WsFrame.Continuation<P>, context)
      is WsFrame.Text<*> -> textCodec.wireSize(value as WsFrame.Text<P>, context)
      is WsFrame.Binary<*> -> binaryCodec.wireSize(value as WsFrame.Binary<P>, context)
      is WsFrame.Close -> WsFrameCloseCodec.wireSize(value, context)
      is WsFrame.Ping<*> -> pingCodec.wireSize(value as WsFrame.Ping<P>, context)
      is WsFrame.Pong<*> -> pongCodec.wireSize(value as WsFrame.Pong<P>, context)
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = WsFrame.peekFrameSize(stream, baseOffset)

  public companion object {
    public fun <P : Payload> decodeAggregating(
      buffer: ReadBuffer,
      context: DecodeContext,
      onContinuation: (WsFrameContinuationCodec.Partial<P>) -> WsFrame.Continuation<P> = { _ -> throw DecodeException(fieldPath = "WsFrame.Continuation.handler", bufferPosition = -1, expected = "consumer-supplied Continuation handler", actual = "no handler supplied") },
      onText: (WsFrameTextCodec.Partial<P>) -> WsFrame.Text<P> = { _ -> throw DecodeException(fieldPath = "WsFrame.Text.handler", bufferPosition = -1, expected = "consumer-supplied Text handler", actual = "no handler supplied") },
      onBinary: (WsFrameBinaryCodec.Partial<P>) -> WsFrame.Binary<P> = { _ -> throw DecodeException(fieldPath = "WsFrame.Binary.handler", bufferPosition = -1, expected = "consumer-supplied Binary handler", actual = "no handler supplied") },
      onPing: (WsFramePingCodec.Partial<P>) -> WsFrame.Ping<P> = { _ -> throw DecodeException(fieldPath = "WsFrame.Ping.handler", bufferPosition = -1, expected = "consumer-supplied Ping handler", actual = "no handler supplied") },
      onPong: (WsFramePongCodec.Partial<P>) -> WsFrame.Pong<P> = { _ -> throw DecodeException(fieldPath = "WsFrame.Pong.handler", bufferPosition = -1, expected = "consumer-supplied Pong handler", actual = "no handler supplied") },
    ): WsFrame<P> {
      val discriminatorPosition = buffer.position()
      val __discriminator = FrameHeaderByte1Codec.decode(buffer, context)
      buffer.position(discriminatorPosition)
      val __dispatchValue = __discriminator.opcode
      return when (__dispatchValue) {
        0 -> onContinuation(WsFrameContinuationCodec.partial<P>(buffer, context))
        1 -> onText(WsFrameTextCodec.partial<P>(buffer, context))
        2 -> onBinary(WsFrameBinaryCodec.partial<P>(buffer, context))
        8 -> WsFrameCloseCodec.decode(buffer, context)
        9 -> onPing(WsFramePingCodec.partial<P>(buffer, context))
        10 -> onPong(WsFramePongCodec.partial<P>(buffer, context))
        else -> {
          throw DecodeException(fieldPath = "WsFrame.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0, 1, 2, 8, 9, 10}", actual = """${__dispatchValue}""")
        }
      }
    }
  }
}
