package com.ditchoom.buffer.codec.test.protocols

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
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Deprecated
import kotlin.Int
import kotlin.ReplaceWith
import kotlin.Suppress

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

  override fun wireBytes(`value`: ConnectionStatus, context: EncodeContext): WireByteCount = when (value) {
    is ConnectionStatus.Disconnected -> WireByteCount.exact(1L)
    is ConnectionStatus.Connecting -> WireByteCount.exact(2L)
    is ConnectionStatus.Connected -> WireByteCount.exact(1L)
    is ConnectionStatus.Failed -> WireByteCount.exact(1L)
  }

  @Deprecated(
    message = "Call wireBytes.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: ConnectionStatus, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: ConnectionStatus, context: EncodeContext): Int = 1 + when (value) {
    is ConnectionStatus.Disconnected -> ConnectionStatusDisconnectedCodec.sizeHint(value, context)
    is ConnectionStatus.Connecting -> ConnectionStatusConnectingCodec.sizeHint(value, context)
    is ConnectionStatus.Connected -> ConnectionStatusConnectedCodec.sizeHint(value, context)
    is ConnectionStatus.Failed -> ConnectionStatusFailedCodec.sizeHint(value, context)
  }

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    if (stream.available() - baseOffset < 1) return FrameSize.NeedsMoreData
    val discriminator = stream.peekByte(baseOffset).toInt() and 0xFF
    return when (discriminator) {
      0x00 -> ConnectionStatusDisconnectedCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x01 -> ConnectionStatusConnectingCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x02 -> ConnectionStatusConnectedCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      0x03 -> ConnectionStatusFailedCodec.frameSize(stream, baseOffset + 1).fold(declared = { FrameSize.declared(1, it) }, needsMoreData = { FrameSize.NeedsMoreData }, unframed = { FrameSize.Unframed })
      else -> {
        throw DecodeException(fieldPath = "ConnectionStatus.discriminator", bufferPosition = baseOffset, expected = "one of {0x00, 0x01, 0x02, 0x03}", actual = """0x${discriminator.toString(16).padStart(2, '0').uppercase()}""")
      }
    }
  }

  @Deprecated(
    message = "Call frameSize.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
