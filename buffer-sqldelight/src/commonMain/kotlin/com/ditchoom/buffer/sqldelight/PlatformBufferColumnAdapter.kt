package com.ditchoom.buffer.sqldelight

import app.cash.sqldelight.ColumnAdapter
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer

/**
 * A **copy-at-boundary** SQLDelight [ColumnAdapter] that maps a `BLOB` column (`ByteArray` on the
 * driver side) to a [PlatformBuffer].
 *
 * This is deliberately **not** zero-copy — the SQLDelight driver API is `ByteArray`, and JDBC /
 * Android `Cursor` have already copied the bytes out of SQLite's native memory before the adapter
 * ever runs, so there is no native buffer left to alias. The two directions differ:
 *
 * - **[decode] (DB → app): aliases, no extra copy.** The driver hands us a fresh, app-owned
 *   `ByteArray`; [BufferFactory.wrap] wraps it in place, so the returned buffer shares that array's
 *   storage (positioned for reading, `position = 0`, `limit = size`). No bytes are copied beyond the
 *   driver's own copy-out.
 * - **[encode] (app → DB): copies.** The bytes handed to `bindBytes` must be independent of the
 *   caller's [PlatformBuffer] — aliasing the buffer's backing array into the bound statement is a
 *   mutation hazard (a later write to the buffer would silently corrupt the bound value). So encode
 *   takes an explicit [PlatformBuffer.copyToByteArray] of the buffer's remaining bytes, leaving the
 *   buffer's position unchanged so it stays reusable.
 *
 * For true zero-copy BLOB access you would go under SQLDelight to the `sqlite3` C API with
 * `wrapNativeAddress` scoped reads — a separate concern, not this adapter.
 *
 * @param factory allocator used by [decode] to [wrap][BufferFactory.wrap] the driver bytes
 *   (default [BufferFactory.Default]). `wrap` aliases on every backend, so the choice only affects
 *   the returned buffer's type, not whether a copy happens.
 */
public class PlatformBufferColumnAdapter(
    private val factory: BufferFactory = BufferFactory.Default,
) : ColumnAdapter<PlatformBuffer, ByteArray> {
    /**
     * Wraps the driver's [databaseValue] in a [PlatformBuffer] positioned for reading. Aliases the
     * array — the returned buffer shares its storage; no bytes are copied here.
     */
    override fun decode(databaseValue: ByteArray): PlatformBuffer = factory.wrap(databaseValue)

    /**
     * Returns a fresh `ByteArray` **copy** of [value]'s remaining bytes (`position` until `limit`)
     * for `bindBytes`. Does not consume [value] — its position is restored — so the same buffer can
     * be bound again or reused after this call.
     */
    override fun encode(value: PlatformBuffer): ByteArray {
        val position = value.position()
        return try {
            value.copyToByteArray(value.remaining())
        } finally {
            value.position(position)
        }
    }

    public companion object {
        /** Shared instance backed by [BufferFactory.Default]. */
        public val Default: PlatformBufferColumnAdapter = PlatformBufferColumnAdapter()
    }
}
