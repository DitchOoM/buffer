package com.ditchoom.buffer

/**
 * Bit-masks used when assembling/disassembling multi-byte primitives from individual bytes in the
 * default [ReadBuffer]/[WriteBuffer] implementations. Shift amounts and byte widths use the stdlib
 * `Byte.SIZE_BITS`/`Short.SIZE_BITS`/`Int.SIZE_BITS` and `Short.SIZE_BYTES`/`Int.SIZE_BYTES`
 * constants directly; only the masks (which the stdlib has no named equivalents for) live here.
 *
 * Every value is the literal it replaces — there is no behavior change.
 */
internal object BufferConstants {
    /** Low 8 bits of an Int — isolates a single byte. */
    const val BYTE_MASK = 0xFF

    /** Low 16 bits of an Int — isolates a single Short. */
    const val SHORT_MASK = 0xFFFF

    /** Low 32 bits of a Long — isolates a single Int. */
    const val INT_MASK = 0xFFFFFFFFL

    /** Multiplier that broadcasts a single byte (0..255) into all 8 bytes of a Long. */
    const val BYTE_BROADCAST = 0x0101010101010101L

    /** Multiplier that broadcasts a single Short (0..65535) into all 4 lanes of a Long. */
    const val SHORT_BROADCAST = 0x0001000100010001L

    /** Multiplier that broadcasts a single Int into both halves of a Long. */
    const val INT_BROADCAST = 0x0000000100000001L

    /** Bit shift to position byte 1 (the second-least-significant byte) of an Int — 8 bits. */
    const val BYTE_1_SHIFT = 8

    /** Bit shift to position byte 2 of an Int — 16 bits. */
    const val BYTE_2_SHIFT = 16

    /** Bit shift to position byte 3 (the most-significant byte) of an Int — 24 bits. */
    const val BYTE_3_SHIFT = 24

    /** Mask for `index % 4` (the byte lane within a 4-byte word), used by WebSocket XOR masking. */
    const val WORD_BYTE_MASK = 3

    /** Index of the last byte (byte 3) within a 4-byte word. */
    const val LAST_WORD_BYTE_INDEX = 3

    /** Bit shift to position byte 4 of a Long — 32 bits. */
    const val BYTE_4_SHIFT = 32

    /** Bit shift to position byte 5 of a Long — 40 bits. */
    const val BYTE_5_SHIFT = 40

    /** Bit shift to position byte 6 of a Long — 48 bits. */
    const val BYTE_6_SHIFT = 48

    /** Bit shift to position byte 7 (the most-significant byte) of a Long — 56 bits. */
    const val BYTE_7_SHIFT = 56

    // Byte-lane offsets within an 8-byte word, used by the unrolled load/store loops.
    const val WORD_BYTE_3 = 3
    const val WORD_BYTE_4 = 4
    const val WORD_BYTE_5 = 5
    const val WORD_BYTE_6 = 6
    const val WORD_BYTE_7 = 7
}
