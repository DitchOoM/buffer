@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_rand_bytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux CSPRNG backed by BoringSSL `RAND_bytes`, written straight into the buffer. */
actual fun cryptoRandomInto(dest: WriteBuffer) {
    val n = dest.remaining()
    if (n == 0) return
    dest.withWritablePointer(n) { ptr ->
        // RAND_bytes never fails in BoringSSL, but surface a non-success status rather than
        // hand back possibly-non-random bytes.
        val status = bcl_rand_bytes(ptr.reinterpret(), n.convert())
        check(status == BCL_OK) { "RAND_bytes failed (status=$status)" }
    }
}
