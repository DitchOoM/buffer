package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
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

public object SignedAndUnsignedMixCodec : Codec<SignedAndUnsignedMix> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedAndUnsignedMix {
    val __batch1 = buffer.readInt()
    val signed: kotlin.Byte
    val unsigned: kotlin.UByte
    val signedShort: kotlin.Short
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      signed = (__batch1 ushr 24 and 0xFF).toByte()
      unsigned = (__batch1 ushr 16 and 0xFF).toUByte()
      signedShort = (__batch1 and 0xFFFF).toShort()
    } else {
      signed = (__batch1 and 0xFF).toByte()
      unsigned = (__batch1 ushr 8 and 0xFF).toUByte()
      signedShort = (__batch1 ushr 16 and 0xFFFF).toShort()
    }
    return SignedAndUnsignedMix(signed = signed, unsigned = unsigned, signedShort = signedShort)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedAndUnsignedMix,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeInt(((value.signed.toInt() and 0xFF) shl 24) or ((value.unsigned.toInt() and 0xFF) shl 16) or (value.signedShort.toInt() and 0xFFFF))
    } else {
      buffer.writeInt((value.signed.toInt() and 0xFF) or ((value.unsigned.toInt() and 0xFF) shl 8) or ((value.signedShort.toInt() and 0xFFFF) shl 16))
    }
  }

  override fun wireBytes(`value`: SignedAndUnsignedMix, context: EncodeContext): WireByteCount = WireByteCount.exact(4L)

  @Deprecated(
    message = "Call wireBytes, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("wireBytes(value, context)"),
  )
  @Suppress("DEPRECATION")
  override fun wireSize(`value`: SignedAndUnsignedMix, context: EncodeContext): WireSize = wireBytes(value, context).toWireSize()

  override fun sizeHint(`value`: SignedAndUnsignedMix, context: EncodeContext): Int = 4

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 4L)

  @Deprecated(
    message = "Call frameSize, which does not box. Removed in the next major release.",
    replaceWith = ReplaceWith("frameSize(stream, baseOffset)"),
  )
  @Suppress("DEPRECATION")
  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = frameSize(stream, baseOffset).toPeekResult(stream.available() - baseOffset)
}
