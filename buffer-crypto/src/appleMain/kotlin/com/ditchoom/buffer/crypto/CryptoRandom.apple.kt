@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** Apple CSPRNG backed by Security.framework `SecRandomCopyBytes`, written straight into the buffer. */
actual fun cryptoRandomInto(dest: WriteBuffer) {
    val n = dest.remaining()
    if (n == 0) return
    dest.withWritablePointer(n) { ptr ->
        // SecRandomCopyBytes returns errSecSuccess (0) on success; on failure the destination
        // bytes are undefined, so surface it rather than hand back non-random data.
        val status = SecRandomCopyBytes(kSecRandomDefault, n.convert(), ptr)
        check(status == errSecSuccess) { "SecRandomCopyBytes failed (OSStatus=$status)" }
    }
}

/** Allocation-free secure [Int]: `SecRandomCopyBytes` into a stack-scoped `Int`. */
internal actual fun cryptoRandomInt(): Int =
    memScoped {
        val holder = alloc<IntVar>()
        val status = SecRandomCopyBytes(kSecRandomDefault, Int.SIZE_BYTES.convert(), holder.ptr)
        check(status == errSecSuccess) { "SecRandomCopyBytes failed (OSStatus=$status)" }
        holder.value
    }
