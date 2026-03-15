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
