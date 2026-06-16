@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/** Apple CSPRNG backed by Security.framework `SecRandomCopyBytes`, written straight into the buffer. */
actual fun cryptoRandomInto(dest: WriteBuffer) {
    val n = dest.remaining()
    if (n == 0) return
    dest.withWritablePointer(n) { ptr -> SecRandomCopyBytes(kSecRandomDefault, n.convert(), ptr) }
}
