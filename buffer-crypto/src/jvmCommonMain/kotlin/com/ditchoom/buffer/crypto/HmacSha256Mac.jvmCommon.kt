@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.toByteArray
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** JVM/Android HMAC-SHA256 backed by the JCA [Mac]. */
actual class HmacSha256Mac actual constructor(
    key: ReadBuffer,
) : AutoCloseable {
    private val mac =
        Mac.getInstance("HmacSHA256").apply {
            val managed = key.managedMemoryAccess
            val spec =
                if (managed != null) {
                    // SecretKeySpec copies the requested range, so no aliasing of the source buffer.
                    SecretKeySpec(
                        managed.backingArray,
                        managed.arrayOffset + key.position(),
                        key.remaining(),
                        "HmacSHA256",
                    )
                } else {
                    SecretKeySpec(key.toByteArray(), "HmacSHA256")
                }
            init(spec)
        }
    private var finalized = false

    actual fun update(input: ReadBuffer): HmacSha256Mac {
        check(!finalized) { "mac already finalized" }
        mac.updateRemaining(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        // JCA resets the mac for reuse; the cross-platform contract is one-shot, so reject reuse.
        check(!finalized) { "mac already finalized" }
        // doFinalInto validates capacity BEFORE consuming the JCA state, so a too-small
        // dest throws with the MAC still intact — the finalize stays retryable (C1).
        mac.doFinalInto(dest)
        finalized = true
    }

    actual override fun close() {
        if (finalized) return
        finalized = true
        mac.reset()
    }
}
