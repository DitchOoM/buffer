package com.ditchoom.buffer.codec.test.protocols.simplegeneric

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int
import kotlin.UByte

public class SimpleGenericFrameCommandCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<SimpleGenericFrame.Command<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SimpleGenericFrame.Command<P> {
    val counter = buffer.readUByte()
    val payload = payloadCodec.decode(buffer, context)
    return SimpleGenericFrame.Command<P>(counter = counter, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SimpleGenericFrame.Command<P>,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.counter)
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireBytes(`value`: SimpleGenericFrame.Command<P>, context: EncodeContext): WireByteCount = WireByteCount.BackPatch

  override fun sizeHint(`value`: SimpleGenericFrame.Command<P>, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = Companion.frameSize(stream, baseOffset)

  public class Partial<P : Payload> internal constructor(
    public val counter: UByte,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): SimpleGenericFrame.Command<P> {
      val payload = payloadCodec.decode(buffer, context)
      return SimpleGenericFrame.Command<P>(counter = counter, payload = payload)
    }
  }

  public companion object : FrameDetector {
    override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed

    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val counter = buffer.readUByte()
      return Partial<P>(counter = counter, buffer = buffer, context = context)
    }
  }
}
