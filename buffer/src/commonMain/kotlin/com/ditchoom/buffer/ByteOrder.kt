package com.ditchoom.buffer

enum class ByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
    ;

    companion object {
        /**
         * Native byte order of the platform. All supported platforms (x86, ARM, WASM) are little-endian,
         * so this is an alias for [LITTLE_ENDIAN]. Using NATIVE avoids byte-swap overhead.
         */
        val NATIVE: ByteOrder = LITTLE_ENDIAN
    }
}

// Endian-swap helpers — used by generated codec code when a batched read/write
// must canonicalize bytes between the wire order and the buffer's runtime byte
// order. Delegates to Kotlin stdlib's `reverseBytes()` extension (common-stdlib
// since 1.5), which compiles to a platform intrinsic where one is available:
//   - JVM: `java.lang.{Short,Integer,Long}.reverseBytes()` → BSWAP / REV
//   - Native: Kotlin/Native intrinsic → BSWAP / REV
//   - JS / WASM: pure shift/or (no hardware byte-swap)
// Top-level wrappers keep the codec-facing call sites stable (`swapBytes(x)`)
// independent of stdlib evolution and avoid any extension-resolution ambiguity
// at the generated-import site.

fun swapBytes(value: Short): Short = value.reverseBytes()

fun swapBytes(value: Int): Int = value.reverseBytes()

fun swapBytes(value: Long): Long = value.reverseBytes()
