package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object SignedAndUnsignedMixCodec : Codec<SignedAndUnsignedMix> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): SignedAndUnsignedMix {
    val __batch23 = buffer.readInt()
    val signed: kotlin.Byte
    val unsigned: kotlin.UByte
    val signedShort: kotlin.Short
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      signed = (__batch23 ushr 24 and 0xFF).toByte()
      unsigned = (__batch23 ushr 16 and 0xFF).toUByte()
      signedShort = (__batch23 and 0xFFFF).toShort()
    } else {
      signed = (__batch23 and 0xFF).toByte()
      unsigned = (__batch23 ushr 8 and 0xFF).toUByte()
      signedShort = (__batch23 ushr 16 and 0xFFFF).toShort()
    }
    return SignedAndUnsignedMix(signed = signed, unsigned = unsigned, signedShort = signedShort)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: SignedAndUnsignedMix,
    context: EncodeContext,
  ) {
    if (buffer.byteOrder == ByteOrder.BIG_ENDIAN) {
      buffer.writeInt((((value.signed.toInt() and 0xFF) shl 24) or ((value.unsigned.toInt() and 0xFF) shl 16) or (value.signedShort.toInt() and 0xFFFF)).toInt())
    } else {
      buffer.writeInt(((value.signed.toInt() and 0xFF) or ((value.unsigned.toInt() and 0xFF) shl 8) or ((value.signedShort.toInt() and 0xFFFF) shl 16)).toInt())
    }
  }

  override fun wireSize(`value`: SignedAndUnsignedMix, context: EncodeContext): WireSize = WireSize.Exact(4)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 4) PeekResult.Complete(4) else PeekResult.NeedsMoreData
}
