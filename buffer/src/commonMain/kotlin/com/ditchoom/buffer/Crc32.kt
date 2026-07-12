package com.ditchoom.buffer

// CRC-32 content checksum over a byte region.
//
// This is the standard CRC-32 (ISO 3309 / ITU-T V.42 / zlib / PNG), reflected input and output,
// polynomial 0x04C11DB7 (reversed 0xEDB88320), init/xorout 0xFFFFFFFF — the check value of the
// ASCII string "123456789" is 0xCBF43926. It is distinct from [hashRange]'s FNV-1a-64 (a
// bucketing digest): CRC-32 is the wire checksum protocols specify, e.g. STUN FINGERPRINT
// (RFC 8489 §14.7, which is crc32 xor 0x5354554E).
//
// Implemented as a top-level extension in the same family as hash64()/regionEquals() rather than a
// ReadBuffer member: no protocol needs a native-accelerated CRC today (the covered spans — a STUN
// message, a PNG chunk — are small), so a common table walk keeps every buffer implementation
// sharing one path. Promote to a member with a cinterop override (cf. buf_fnv1a_64) if a hot bulk
// CRC path ever appears.
//
// The input is consumed in bulk 8-byte words (one getLongUnchecked per 8 bytes, byte-tail last),
// the same pattern as bufferHashCode()/contentEquals() — a single up-front bounds check then
// unchecked reads. Unlike FNV, a CRC is a byte-ordered wire value, so the word is split into its
// bytes in ascending address order (which depends on byteOrder); the two branches below are the
// only difference from a per-byte loop. The table step itself stays per-byte — that is intrinsic
// to a 256-entry table CRC, not an extra copy.

/** CRC-32 polynomial in reversed (LSB-first) bit order — the reflected form the table walk uses. */
private const val CRC32_REVERSED_POLYNOMIAL = 0xEDB88320.toInt()

/** Bits folded per table entry when building [CRC32_TABLE], and the CRC shift per byte step. */
private const val CRC32_TABLE_BITS = 8

/** 32-bit word width of a table entry. */
private const val CRC32_ENTRY_BYTES = 4

/** Number of entries in the lookup table (one per byte value). */
private const val CRC32_TABLE_ENTRIES = 1 shl CRC32_TABLE_BITS

/** Low-byte mask for indexing the table with `(crc xor inputByte) and BYTE_MASK`. */
private const val BYTE_MASK = 0xFF

/** Bit width of a byte — the per-lane shift when splitting a bulk word into its 8 bytes. */
private const val BYTE_BITS = 8

/** Shift landing the most-significant byte of a big-endian word (byte at the lowest address). */
private const val WORD_HIGH_BYTE_SHIFT = 56

/**
 * 256-entry lookup table computed once at class-init from [CRC32_REVERSED_POLYNOMIAL], stored in a
 * shared read-only managed buffer (one 32-bit word per entry) rather than an [IntArray] — the
 * zero-copy buffer discipline this library exists for (cf. `Sha256Core.K`). Entry `i` is
 * `getInt(i * CRC32_ENTRY_BYTES)`.
 */
private val CRC32_TABLE: ReadBuffer =
    BufferFactory.managed().allocate(CRC32_TABLE_ENTRIES * CRC32_ENTRY_BYTES, ByteOrder.BIG_ENDIAN).apply {
        for (n in 0 until CRC32_TABLE_ENTRIES) {
            var c = n
            repeat(CRC32_TABLE_BITS) {
                c = if (c and 1 != 0) CRC32_REVERSED_POLYNOMIAL xor (c ushr 1) else c ushr 1
            }
            set(n * CRC32_ENTRY_BYTES, c)
        }
    }

/** Folds one input byte [b] into the running [crc]. */
private inline fun crc32Step(
    crc: Int,
    b: Int,
): Int = CRC32_TABLE.getInt(((crc xor b) and BYTE_MASK) * CRC32_ENTRY_BYTES) xor (crc ushr CRC32_TABLE_BITS)

/**
 * CRC-32 (ISO 3309 / zlib) over the absolute byte range `[offset, offset + length)`.
 *
 * Reads the region in bulk 8-byte words after one up-front bounds check, and does not change
 * [position]. The value is byte-order-independent (a per-byte checksum) even though the bulk read
 * is not — the word is decomposed into bytes in address order. See the file header for the exact
 * CRC-32 variant.
 *
 * @param offset absolute index of the first byte
 * @param length number of bytes to checksum
 */
fun ReadBuffer.crc32(
    offset: Int,
    length: Int,
): UInt {
    requireRange(offset, length)
    var crc = -1 // 0xFFFFFFFF
    var i = 0
    val bulkEnd = length - Long.SIZE_BYTES
    if (byteOrder == ByteOrder.BIG_ENDIAN) {
        while (i <= bulkEnd) {
            val w = getLongUnchecked(offset + i)
            var shift = WORD_HIGH_BYTE_SHIFT
            while (shift >= 0) {
                crc = crc32Step(crc, (w ushr shift).toInt() and BYTE_MASK)
                shift -= BYTE_BITS
            }
            i += Long.SIZE_BYTES
        }
    } else {
        while (i <= bulkEnd) {
            val w = getLongUnchecked(offset + i)
            var shift = 0
            while (shift <= WORD_HIGH_BYTE_SHIFT) {
                crc = crc32Step(crc, (w ushr shift).toInt() and BYTE_MASK)
                shift += BYTE_BITS
            }
            i += Long.SIZE_BYTES
        }
    }
    while (i < length) {
        crc = crc32Step(crc, getUnchecked(offset + i).toInt())
        i++
    }
    return crc.inv().toUInt()
}

/**
 * CRC-32 over the remaining bytes (`position()` until `limit()`). Convenience for [crc32];
 * does not change [position].
 */
fun ReadBuffer.crc32(): UInt = crc32(position(), remaining())
