package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Decoder
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.swapBytes
import kotlin.Int
import kotlin.UByte

public class Http2FrameDataCodec<P : Payload>(
  private val payloadCodec: Codec<P>,
) : Codec<Http2Frame.Data<P>> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http2Frame.Data<P> {
    val headerRaw = buffer.readInt()
    val header = Http2LengthAndType((if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw)).toUInt())
    val flags = buffer.readUByte()
    val streamId = Http2StreamId(buffer.readUInt())
    val payload = payloadCodec.decode(buffer, context)
    return Http2Frame.Data<P>(header = header, flags = flags, streamId = streamId, payload = payload)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http2Frame.Data<P>,
    context: EncodeContext,
  ) {
    val headerRaw = value.header.raw.toInt()
    buffer.writeInt(if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw))
    buffer.writeUByte(value.flags)
    buffer.writeUInt(value.streamId.raw)
    payloadCodec.encode(buffer, value.payload, context)
  }

  override fun wireSize(`value`: Http2Frame.Data<P>, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun sizeHint(`value`: Http2Frame.Data<P>, context: EncodeContext): Int = 9

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming

  public class Partial<P : Payload> internal constructor(
    public val `header`: Http2LengthAndType,
    public val flags: UByte,
    public val streamId: Http2StreamId,
    private val buffer: ReadBuffer,
    private val context: DecodeContext,
  ) {
    public fun complete(payloadCodec: Decoder<P>): Http2Frame.Data<P> {
      val payload = payloadCodec.decode(buffer, context)
      return Http2Frame.Data<P>(header = header, flags = flags, streamId = streamId, payload = payload)
    }
  }

  public companion object {
    public fun <P : Payload> partial(buffer: ReadBuffer, context: DecodeContext): Partial<P> {
      val headerRaw = buffer.readInt()
      val header = Http2LengthAndType((if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) headerRaw else swapBytes(headerRaw)).toUInt())
      val flags = buffer.readUByte()
      val streamId = Http2StreamId(buffer.readUInt())
      return Partial<P>(header = header, flags = flags, streamId = streamId, buffer = buffer, context = context)
    }
  }
}
