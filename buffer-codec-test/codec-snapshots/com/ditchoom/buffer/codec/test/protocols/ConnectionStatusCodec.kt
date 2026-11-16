package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ConnectionStatusCodec : Codec<ConnectionStatus> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ConnectionStatus {
    val discriminatorPosition = buffer.position()
    val discriminator = buffer.readUByte().toInt()
    return when (discriminator) {
      0x00 -> ConnectionStatusDisconnectedCodec.decode(buffer, context)
      0x01 -> ConnectionStatusConnectingCodec.decode(buffer, context)
      0x02 -> ConnectionStatusConnectedCodec.decode(buffer, context)
      0x03 -> ConnectionStatusFailedCodec.decode(buffer, context)
      else -> {
        throw DecodeException(fieldPath = "ConnectionStatus.discriminator", bufferPosition = discriminatorPosition, expected = "one of {0x00, 0x01, 0x02, 0x03}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ConnectionStatus,
    context: EncodeContext,
  ) {
    when (value) {
      is ConnectionStatus.Disconnected -> {
        buffer.writeUByte(0x00.toUByte())
        ConnectionStatusDisconnectedCodec.encode(buffer, value, context)
      }
      is ConnectionStatus.Connecting -> {
        buffer.writeUByte(0x01.toUByte())
        ConnectionStatusConnectingCodec.encode(buffer, value, context)
      }
      is ConnectionStatus.Connected -> {
        buffer.writeUByte(0x02.toUByte())
        ConnectionStatusConnectedCodec.encode(buffer, value, context)
      }
      is ConnectionStatus.Failed -> {
        buffer.writeUByte(0x03.toUByte())
        ConnectionStatusFailedCodec.encode(buffer, value, context)
      }
    }
  }

  override fun wireSize(`value`: ConnectionStatus, context: EncodeContext): WireSize = when (value) {
    is ConnectionStatus.Disconnected -> WireSize.Exact(1)
    is ConnectionStatus.Connecting -> WireSize.Exact(2)
    is ConnectionStatus.Connected -> WireSize.Exact(1)
    is ConnectionStatus.Failed -> WireSize.Exact(1)
  }

  override fun sizeHint(`value`: ConnectionStatus, context: EncodeContext): Int = 1 + when (value) {
    is ConnectionStatus.Disconnected -> ConnectionStatusDisconnectedCodec.sizeHint(value, context)
    is ConnectionStatus.Connecting -> ConnectionStatusConnectingCodec.sizeHint(value, context)
    is ConnectionStatus.Connected -> ConnectionStatusConnectedCodec.sizeHint(value, context)
    is ConnectionStatus.Failed -> ConnectionStatusFailedCodec.sizeHint(value, context)
  }

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek {
    if (stream.available() - baseOffset < 1) return FramePeek.needsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> ConnectionStatusDisconnectedCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      0x01 -> ConnectionStatusConnectingCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      0x02 -> ConnectionStatusConnectedCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      0x03 -> ConnectionStatusFailedCodec.peekFrame(stream, baseOffset + 1).withPrefix(1)
      else -> {
        throw DecodeException(fieldPath = "ConnectionStatus.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01, 0x02, 0x03}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
