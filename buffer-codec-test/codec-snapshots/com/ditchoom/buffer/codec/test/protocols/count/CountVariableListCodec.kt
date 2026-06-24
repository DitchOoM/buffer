package com.ditchoom.buffer.codec.test.protocols.count

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.UnsignedVarIntCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object CountVariableListCodec : Codec<CountVariableList> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): CountVariableList {
    val __namesCount = UnsignedVarIntCodec.decode(buffer, context).toInt()
    val names = ArrayList<CountNamed>(__namesCount.coerceIn(0, 1_024))
    repeat(__namesCount) {
      names += CountNamedCodec.decode(buffer, context)
    }
    return CountVariableList(names = names)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: CountVariableList,
    context: EncodeContext,
  ) {
    UnsignedVarIntCodec.encode(buffer, value.names.size.toUInt(), context)
    for (__elem in value.names) {
      CountNamedCodec.encode(buffer, __elem, context)
    }
  }

  override fun wireSize(`value`: CountVariableList, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
