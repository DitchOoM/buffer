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
) {
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

    actual fun update(input: ReadBuffer): HmacSha256Mac {
        mac.updateRemaining(input)
        return this
    }

    actual fun doFinalInto(dest: WriteBuffer) {
        mac.doFinalInto(dest)
    }
}
