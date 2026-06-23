package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer

/**
 * A [PlatformBuffer] decorator that zeroes its full backing storage when it is freed,
 * so cryptographic key material does not linger on the heap after use.
 *
 * The constructor is `internal`: a [SecureBuffer] can only originate from
 * [SecureBufferFactory] (via [BufferFactory.secure]). This guarantees that anything typed
 * "secure" really does wipe — there is no way to hand-roll an unwiped one — and that the
 * full-capacity wipe (not just `remaining()`) is performed in exactly one audited place.
 *
 * The wipe runs on every standard teardown path, because they all funnel through
 * [freeNativeMemory]: `use {}`, `freeIfNeeded()`, `freeAll()`, and an explicit
 * `freeNativeMemory()` call. There is **no GC finalizer hook** — a buffer that is never
 * explicitly freed is never wiped — so secret-bearing buffers should come from a
 * deterministic factory and be released with `use {}`:
 *
 * ```kotlin
 * BufferFactory.deterministic().secure().allocate(32).use { key ->
 *     // ... key is zeroed here, at end of block ...
 * }
 * ```
 *
 * **Best-effort.** On GC-managed backings (heap [ByteArray], JS `Int8Array`) the runtime
 * may already have copied the bytes elsewhere before the wipe; the guarantee is strongest
 * on native/direct backings (direct `ByteBuffer`, FFM, malloc, WASM linear memory). This
 * is defense-in-depth, not a defense against a live-memory adversary.
 */
class SecureBuffer internal constructor(
    private val inner: PlatformBuffer,
) : PlatformBuffer by inner {
    private var wiped = false

    override fun freeNativeMemory() {
        if (!wiped) {
            wiped = true
            try {
                // Address the WHOLE buffer, not just remaining() — fill() writes
                // position..limit, so widen the window to cover every byte first.
                inner.position(0)
                inner.setLimit(inner.capacity)
                // Byte fill (not the Int-pattern overload, which rejects non-multiple-of-4
                // lengths) so buffers of any capacity — e.g. P-521's 66 bytes — are fully wiped.
                inner.fill(0.toByte())
            } catch (_: Throwable) {
                // A best-effort wipe must never mask the real free below; swallow and free.
            }
        }
        inner.freeNativeMemory()
    }

    override fun slice(byteOrder: ByteOrder): PlatformBuffer = inner.slice(byteOrder)
}

/**
 * A [BufferFactory] decorator whose buffers wipe their backing storage on free. Composes
 * over any delegate factory — `BufferFactory.deterministic().secure()`,
 * `someFactory.withPooling(pool).secure()`, etc. — so the secure-erase behavior layers
 * on top of whatever allocation strategy the delegate provides.
 *
 * [maxAllocationBytes] is an explicit upper bound on any single secure allocation. Secure
 * buffers hold key material and scratch, which is small; a request larger than the bound is
 * almost always a bug or an attacker-supplied length reaching `allocate`. Enforcing the cap
 * turns an unbounded native-memory request (a denial-of-service vector) into a deterministic
 * [IllegalArgumentException], thrown in common code before any platform allocation, so the
 * behavior is byte-identical on every target.
 */
internal class SecureBufferFactory(
    private val delegate: BufferFactory,
    private val maxAllocationBytes: Int,
) : BufferFactory {
    init {
        require(maxAllocationBytes >= 1) {
            "maxAllocationBytes must be >= 1, was $maxAllocationBytes"
        }
    }

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        require(size in 0..maxAllocationBytes) {
            "secure allocation of $size bytes exceeds the configured maximum of $maxAllocationBytes"
        }
        return SecureBuffer(zeroInitBuffer(delegate.allocate(size, byteOrder)))
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        require(array.size <= maxAllocationBytes) {
            "secure wrap of ${array.size} bytes exceeds the configured maximum of $maxAllocationBytes"
        }
        // wrap takes ownership of an array whose existing contents are the secret to protect —
        // so it does NOT zero-init (that would destroy the caller's data). The wipe still runs
        // on free. This mirrors the difference between allocate (fresh, zeroed scratch) and
        // wrap (adopt existing bytes).
        return SecureBuffer(delegate.wrap(array, byteOrder))
    }

    /**
     * Wraps a pool-borrowed (or otherwise already-allocated) buffer in a [SecureBuffer], zeroing
     * its full backing first. This is the allocate-equivalent for a borrowed buffer: it clears any
     * data the previous borrower left behind (a lingering secret, or stale bytes a fresh secure
     * allocation would never expose) before the buffer is used, and the [SecureBuffer] wipes again
     * on free before returning the buffer to the pool.
     *
     * The [maxAllocationBytes] cap is intentionally NOT re-checked here: `decorate` receives an
     * existing buffer rather than a caller-supplied length, so there is no untrusted size to bound.
     * On the `secure().withPooling(pool)` path the requested size reaches the pool before this
     * wrap, so that composition defers sizing to the pool — use `withPooling(pool).secure()`
     * (secure outermost) or `.withSizeLimit(n)` when an attacker controls the length.
     */
    override fun decorate(buffer: PlatformBuffer): PlatformBuffer = SecureBuffer(zeroInitBuffer(buffer))
}

/**
 * Zero-initializes the full backing of [buffer] — the allocate-time mirror of [SecureBuffer]'s
 * wipe-on-free. Secure scratch must be deterministically zero on every platform: crypto code
 * (e.g. HKDF's empty-salt zero block) relies on a fresh secure buffer reading as zero, and the
 * native Linux backing ([com.ditchoom.buffer.NativeBuffer], raw `malloc`) does not zero on its
 * own. Zeros the whole capacity (not just `remaining()`) so no slack bytes carry prior contents,
 * then restores the position/limit the buffer had so the `allocate` contract is unchanged
 * (including pooled buffers where capacity > requested size).
 */
internal fun zeroInitBuffer(buffer: PlatformBuffer): PlatformBuffer {
    val savedPosition = buffer.position()
    val savedLimit = buffer.limit()
    buffer.position(0)
    buffer.setLimit(buffer.capacity)
    // Byte fill, not the Int-pattern overload: the latter requires the length to be a
    // multiple of 4 and throws otherwise (e.g. P-521's 66-byte scratch, odd sizes).
    buffer.fill(0.toByte())
    buffer.position(savedPosition)
    buffer.setLimit(savedLimit)
    return buffer
}

/**
 * Wraps this buffer in a [SecureBuffer] so its full backing is zeroed when it is freed — the
 * public, à-la-carte entry point to the secure-erase behavior that [BufferFactory.secure] applies
 * at the factory level.
 *
 * The wipe runs only on an explicit teardown — `use {}`, `freeNativeMemory()`, `freeIfNeeded()`,
 * or `freeAll()` — because there is no GC finalizer hook. A buffer that is never freed is never
 * wiped, so prefer a deterministic backing and `use {}`, or the scoped helpers
 * [withSecureBuffer][com.ditchoom.buffer.pool.BufferPool] / [secureFixedPool] which guarantee the
 * wipe runs:
 * ```kotlin
 * BufferFactory.deterministic().allocate(32).toSecureBuffer().use { key -> /* ... */ }
 * ```
 *
 * @param zeroFirst if true, zero the full backing *before* returning (use when adopting a reused
 *   or pooled buffer whose prior contents must not leak in). Defaults to false — wrapping a buffer
 *   whose existing bytes are the secret you want protected, which must not be destroyed.
 */
fun PlatformBuffer.toSecureBuffer(zeroFirst: Boolean = false): PlatformBuffer = SecureBuffer(zeroInitIf(zeroFirst))

private fun PlatformBuffer.zeroInitIf(zero: Boolean): PlatformBuffer = if (zero) zeroInitBuffer(this) else this

/**
 * Returns a factory whose buffers zero their backing storage when freed (see [SecureBuffer]).
 *
 * Layer it over the allocation strategy you want; for key material prefer a deterministic
 * delegate so the wipe is guaranteed to run:
 * ```kotlin
 * val secure = BufferFactory.deterministic().secure()
 * secure.allocate(32).use { key -> /* ... */ } // zeroed at end of block
 * ```
 *
 * [maxAllocationBytes] bounds every secure allocation/wrap — a defense against
 * resource-exhaustion (DoS) when an untrusted, length-prefixed input can reach `allocate`.
 * Requests above the bound throw [IllegalArgumentException] before any memory is reserved.
 * It defaults to a generous 16 MiB backstop ([DEFAULT_MAX_SECURE_ALLOCATION_BYTES]) so the
 * safe behavior is the default — no realistic key material or crypto scratch approaches it.
 * Pass a tighter, protocol-specific bound when an attacker controls the length (key material
 * is normally well under a kilobyte):
 * ```kotlin
 * val secure = BufferFactory.deterministic().secure(maxAllocationBytes = 4 * 1024)
 * secure.allocate(attackerControlledLength) // throws if length > 4096
 * ```
 *
 * @param maxAllocationBytes upper bound, in bytes, on a single secure buffer. Must be >= 1.
 */
fun BufferFactory.secure(maxAllocationBytes: Int = DEFAULT_MAX_SECURE_ALLOCATION_BYTES): BufferFactory =
    SecureBufferFactory(this, maxAllocationBytes)

/**
 * Default upper bound for [secure]: a 16 MiB backstop. Generous enough that no legitimate key
 * material or crypto scratch reaches it, small enough to bound a runaway or attacker-driven
 * allocation. Override with a tighter, protocol-specific cap when parsing untrusted input.
 */
const val DEFAULT_MAX_SECURE_ALLOCATION_BYTES: Int = 16 * 1024 * 1024
