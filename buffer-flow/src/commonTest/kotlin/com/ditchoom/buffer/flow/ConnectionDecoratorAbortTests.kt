package com.ditchoom.buffer.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Connection.abort] defaults to [Connection.close] so the addition stays source-compatible. That
 * default is a trap for *decorators*: a wrapper that overrides only `close()` inherits the default
 * `abort()`, so `abort()` on the wrapper silently becomes a graceful drain and never reaches the
 * inner connection's real abort — turning the escape hatch for a stalled peer back into the very
 * hang it exists to break out of.
 *
 * Rule this pins: any decorator overriding `close()` must override `abort()`.
 */
class ConnectionDecoratorAbortTests {
    private class Probe : Connection<String> {
        var closed = false
        var aborted = false

        override val id: Long = 7L

        override suspend fun send(message: String) = Unit

        override fun receive(): Flow<String> = emptyFlow()

        override suspend fun close() {
            closed = true
        }

        override suspend fun abort() {
            aborted = true
        }
    }

    @Test
    fun mapNotNullForwardsAbortToTheInnerConnection() =
        runTest {
            val probe = Probe()
            val wrapped = probe.mapNotNull<String, String>(encode = { it }, decode = { it })

            wrapped.abort()

            assertTrue(probe.aborted, "abort() must reach the inner connection's real abort")
            assertFalse(probe.closed, "abort() must not degrade into a graceful close")
        }

    @Test
    fun mapNotNullStillForwardsCloseSeparately() =
        runTest {
            val probe = Probe()
            val wrapped = probe.mapNotNull<String, String>(encode = { it }, decode = { it })

            wrapped.close()

            assertTrue(probe.closed)
            assertFalse(probe.aborted, "close() must stay graceful")
        }
}
