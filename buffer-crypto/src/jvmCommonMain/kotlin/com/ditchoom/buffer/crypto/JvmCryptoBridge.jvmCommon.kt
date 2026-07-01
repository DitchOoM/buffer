package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.toNativeData
import java.security.MessageDigest
import javax.crypto.Mac

/**
 * Feeds the remaining bytes of [buffer] into this digest without disturbing the buffer's
 * position. Zero-copy on both backings: heap buffers go through their own backing array,
 * direct buffers through a read-only `ByteBuffer` view ([toNativeData] is non-destructive
 * and scoped to position..limit). No intermediate array is allocated by this module.
 */
internal fun MessageDigest.updateRemaining(buffer: ReadBuffer) {
    if (buffer.remaining() == 0) return
    val managed = buffer.managedMemoryAccess
    if (managed != null) {
        update(managed.backingArray, managed.arrayOffset + buffer.position(), buffer.remaining())
    } else {
        update(buffer.toNativeData().byteBuffer)
    }
}

/** As [MessageDigest.updateRemaining], for an HMAC. */
internal fun Mac.updateRemaining(buffer: ReadBuffer) {
    if (buffer.remaining() == 0) return
    val managed = buffer.managedMemoryAccess
    if (managed != null) {
        update(managed.backingArray, managed.arrayOffset + buffer.position(), buffer.remaining())
    } else {
        update(buffer.toNativeData().byteBuffer)
    }
}

/**
 * Writes the final digest into [dest] at its current position, advancing it. When [dest] is
 * heap-backed the digest is written straight into the buffer's own backing array (zero-copy,
 * no allocation by this module). Otherwise JCA returns its own array, which we hand to the
 * buffer via `writeBytes` — the only `ByteArray` here is the one the system API produces.
 */
internal fun MessageDigest.digestInto(dest: WriteBuffer) {
    // Validate BEFORE digest(): JCA's digest()/doFinal() consume and auto-reset the engine,
    // so letting a short dest fail inside writeBytes (direct path) — or silently write past
    // the buffer's limit into a larger shared backing array (heap path) — would destroy
    // state the caller could otherwise retry with a correctly-sized buffer.
    require(dest.remaining() >= digestLength) { "dest needs $digestLength bytes remaining, has ${dest.remaining()}" }
    val managed = dest.managedMemoryAccess
    if (managed != null) {
        val pos = dest.position()
        digest(managed.backingArray, managed.arrayOffset + pos, digestLength)
        dest.position(pos + digestLength)
    } else {
        dest.writeBytes(digest())
    }
}

/** As [MessageDigest.digestInto], for an HMAC tag. */
internal fun Mac.doFinalInto(dest: WriteBuffer) {
    // See [MessageDigest.digestInto] — same validate-before-doFinal() rationale.
    require(dest.remaining() >= macLength) { "dest needs $macLength bytes remaining, has ${dest.remaining()}" }
    val managed = dest.managedMemoryAccess
    if (managed != null) {
        val pos = dest.position()
        doFinal(managed.backingArray, managed.arrayOffset + pos)
        dest.position(pos + macLength)
    } else {
        dest.writeBytes(doFinal())
    }
}
