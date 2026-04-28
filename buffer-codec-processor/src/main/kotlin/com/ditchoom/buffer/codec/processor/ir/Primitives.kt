package com.ditchoom.buffer.codec.processor.ir

/** Wire primitive kinds the IR understands. */
enum class PrimitiveKind {
    Bool,
    Byte,
    UByte,
    Short,
    UShort,
    Int,
    UInt,
    Long,
    ULong,
    Float,
    Double,
}

/** Byte order for a multi-byte field. Independent of the annotation-surface enum. */
enum class Endianness { Big, Little }

/** Length-prefix encoding. Mirrors the annotation-surface `LengthPrefix` enum at IR level. */
enum class LengthEncoding {
    Byte,
    Short,
    Int,
    Varint,
}
