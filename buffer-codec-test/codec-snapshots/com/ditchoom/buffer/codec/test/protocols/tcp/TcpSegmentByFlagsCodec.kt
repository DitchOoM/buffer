package com.ditchoom.buffer.codec.test.protocols.tcp

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.toPeekResult
import com.ditchoom.buffer.codec.toWireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

public object TcpSegmentByFlagsCodec : Codec<TcpSegmentByFlags> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): TcpSegmentByFlags {
    val discriminatorPosition = buffer.position()
    val __discriminator = TcpFlagsByteCodec.decode(buffer, context)
    buffer.position(discriminatorPosition)
    val __dispatchValue = __discriminator.flags.toInt()
    return when (__dispatchValue) {
      2 -> TcpSegmentByFlagsSynCodec.decode(buffer, context)
      4 -> TcpSegmentByFlagsRstCodec.decode(buffer, context)
      16 -> TcpSegmentByFlagsAckCodec.decode(buffer, context)
      17 -> TcpSegmentByFlagsFinAckCodec.decode(buffer, context)
      18 -> TcpSegmentByFlagsSynAckCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "TcpSegmentByFlags.discriminator", bufferPosition = discriminatorPosition, expected = "one of {2, 4, 16, 17, 18}", actual = """${__dispatchValue}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: TcpSegmentByFlags,
    context: EncodeContext,
  ) {
    when (value) {
      is TcpSegmentByFlags.Syn -> TcpSegmentByFlagsSynCodec.encode(buffer, value, context)
      is TcpSegmentByFlags.Rst -> TcpSegmentByFlagsRstCodec.encode(buffer, value, context)
      is TcpSegmentByFlags.Ack -> TcpSegmentByFlagsAckCodec.encode(buffer, value, context)
      is TcpSegmentByFlags.FinAck -> TcpSegmentByFlagsFinAckCodec.encode(buffer, value, context)
      is TcpSegmentByFlags.SynAck -> TcpSegmentByFlagsSynAckCodec.encode(buffer, value, context)
    }
  }

  override fun wireBytes(`value`: TcpSegmentByFlags, context: EncodeContext): WireByteCount = when (value) {
    is TcpSegmentByFlags.Syn -> TcpSegmentByFlagsSynCodec.wireBytes(value, context)
    is TcpSegmentByFlags.Rst -> TcpSegmentByFlagsRstCodec.wireBytes(value, context)
    is TcpSegmentByFlags.Ack -> TcpSegmentByFlagsAckCodec.wireBytes(value, context)
    is TcpSegmentByFlags.FinAck -> TcpSegmentByFlagsFinAckCodec.wireBytes(value, context)
    is TcpSegmentByFlags.SynAck -> TcpSegmentByFlagsSynAckCodec.wireBytes(value, context)
  }

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: TcpSegmentByFlags, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: TcpSegmentByFlags, context: EncodeContext): Int = when (value) {
    is TcpSegmentByFlags.Syn -> TcpSegmentByFlagsSynCodec.sizeHint(value, context)
    is TcpSegmentByFlags.Rst -> TcpSegmentByFlagsRstCodec.sizeHint(value, context)
    is TcpSegmentByFlags.Ack -> TcpSegmentByFlagsAckCodec.sizeHint(value, context)
    is TcpSegmentByFlags.FinAck -> TcpSegmentByFlagsFinAckCodec.sizeHint(value, context)
    is TcpSegmentByFlags.SynAck -> TcpSegmentByFlagsSynAckCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val __discRaw = stream.peekByte(baseOffset + 0).toUByte()
    val __discriminator = TcpFlagsByte(__discRaw)
    val __dispatchValue = __discriminator.flags.toInt()
    return when (__dispatchValue) {
      2 -> TcpSegmentByFlagsSynCodec.frameSize(stream, baseOffset)
      4 -> TcpSegmentByFlagsRstCodec.frameSize(stream, baseOffset)
      16 -> TcpSegmentByFlagsAckCodec.frameSize(stream, baseOffset)
      17 -> TcpSegmentByFlagsFinAckCodec.frameSize(stream, baseOffset)
      18 -> TcpSegmentByFlagsSynAckCodec.frameSize(stream, baseOffset)
      else -> {
        throw DecodeException(fieldPath = "TcpSegmentByFlags.discriminator", bufferPosition = baseOffset, expected = "one of {2, 4, 16, 17, 18}", actual = """${__dispatchValue}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
