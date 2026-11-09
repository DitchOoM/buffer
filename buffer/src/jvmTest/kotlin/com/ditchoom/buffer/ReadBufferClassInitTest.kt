package com.ditchoom.buffer

import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import javax.management.ObjectName
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Two threads entering the buffer class hierarchy from opposite ends must not deadlock in JVM
 * class initialization.
 *
 * Every [ReadBuffer] implementation initializes [ReadBuffer] before itself, because the interface
 * declares default methods. So [ReadBuffer]'s own static initialization must never need an
 * implementation class: if it did, a thread initializing [ReadBuffer] and a thread initializing an
 * implementation would each hold the class the other is waiting for, forever, and no deadlock
 * detector reports it (class-initialization waits are not monitor waits).
 *
 * The schedule is forced, not hoped for. The classes are loaded in a fresh [GatedBufferLoader], so
 * nothing in this JVM has initialized them yet. Thread `first` initializes [ReadBuffer]; if that
 * initialization asks for [HeapJvmBuffer] the loader parks it there, holding [ReadBuffer]'s
 * initialization. Thread `second` then initializes [BaseJvmBuffer], and only once a thread dump
 * shows it waiting on [ReadBuffer]'s initialization monitor is `first` released. From there a
 * cycle, if the hierarchy has one, cannot be missed: the thread dump shows both threads waiting on
 * each other's monitor, and the test fails with those two stacks.
 */
class ReadBufferClassInitTest {
    @Test
    fun `initializing ReadBuffer and an implementation from two threads does not deadlock`() {
        val loader = GatedBufferLoader(ReadBufferClassInitTest::class.java.classLoader, gatedClass = HEAP_JVM_BUFFER)
        assertTrue(loader.isRegisteredAsParallelCapable, "the gate must not hold the loader's global lock")
        Class.forName(BASE_JVM_BUFFER, false, loader)

        val first = Initializer("first", READ_BUFFER, loader)
        loader.gateThread(first.thread)
        first.start()
        when (val reached = CompletableFuture.anyOf(first.done, loader.atGate).get()) {
            is GateReached -> {
                val second = Initializer("second", BASE_JVM_BUFFER, loader)
                second.start()
                awaitThreads("`second` to wait on ReadBuffer's initialization while `first` holds it") { dump ->
                    dump.waitsOnInitOf(second.thread, READ_BUFFER)
                }
                loader.release()
                val settled =
                    awaitThreads("both initializations to finish") { dump ->
                        (first.done.isDone && second.done.isDone) || dump.isCycle(first.thread, second.thread)
                    }
                if (!(first.done.isDone && second.done.isDone)) {
                    fail(
                        "class-initialization deadlock: ReadBuffer's static initialization constructs a " +
                            "$HEAP_JVM_BUFFER, while a thread initializing $BASE_JVM_BUFFER waits on " +
                            "ReadBuffer.\n${settled.blockOf(first.thread)}\n\n${settled.blockOf(second.thread)}",
                    )
                }
                first.done.get().rethrow()
                second.done.get().rethrow()
            }
            is InitOutcome -> {
                reached.rethrow()
                val second = Initializer("second", BASE_JVM_BUFFER, loader)
                second.start()
                second.done.get().rethrow()
            }
            else -> fail("unexpected completion: $reached")
        }
    }

    private class GateReached

    private class InitOutcome(
        val failure: Throwable?,
    ) {
        fun rethrow() {
            if (failure != null) throw AssertionError("class initialization failed", failure)
        }
    }

    private class Initializer(
        name: String,
        private val className: String,
        private val loader: ClassLoader,
    ) {
        val done = CompletableFuture<InitOutcome>()
        val thread =
            Thread({
                val failure = runCatching { Class.forName(className, true, loader) }.exceptionOrNull()
                done.complete(InitOutcome(failure))
            }, name).apply { isDaemon = true }

        fun start() = thread.start()
    }

    /**
     * Defines every `com.ditchoom.buffer` class itself (from the parent's bytes), so their static
     * initializers run fresh in this loader, and parks the gated thread when it asks for
     * [gatedClass] until [release].
     */
    private class GatedBufferLoader(
        parent: ClassLoader,
        private val gatedClass: String,
    ) : ClassLoader(parent) {
        val atGate = CompletableFuture<GateReached>()
        private val opened = CompletableFuture<Unit>()
        private val gated = CompletableFuture<Thread>()

        fun gateThread(thread: Thread) {
            gated.complete(thread)
        }

        fun release() {
            opened.complete(Unit)
        }

        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (!name.startsWith(BUFFER_PACKAGE)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                if (name == gatedClass && gated.getNow(null) === Thread.currentThread()) {
                    atGate.complete(GateReached())
                    opened.join()
                }
                val bytes =
                    parent.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
                        ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }

        companion object {
            init {
                registerAsParallelCapable()
            }
        }
    }

    /** One `Thread.print` of this JVM, split into per-thread blocks. */
    private class ThreadDump(
        private val text: String,
    ) {
        fun blockOf(thread: Thread): String =
            text.split("\n\n").firstOrNull { it.startsWith("\"${thread.name}\"") } ?: "(no block for ${thread.name})"

        fun waitsOnInitOf(
            thread: Thread,
            className: String,
        ): Boolean = blockOf(thread).contains("waiting on the Class initialization monitor for $className")

        fun isCycle(
            first: Thread,
            second: Thread,
        ): Boolean =
            waitsOnInitOf(second, READ_BUFFER) &&
                (waitsOnInitOf(first, BASE_JVM_BUFFER) || waitsOnInitOf(first, HEAP_JVM_BUFFER))

        override fun toString(): String = text
    }

    private fun threadDump(): ThreadDump =
        ThreadDump(
            ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "threadPrint",
                arrayOf<Any>(emptyArray<String>()),
                arrayOf(Array<String>::class.java.name),
            ) as String,
        )

    private fun awaitThreads(
        what: String,
        timeout: Duration = AWAIT_TIMEOUT,
        until: (ThreadDump) -> Boolean,
    ): ThreadDump {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val dump = threadDump()
            if (until(dump)) return dump
            if (start.elapsedNow() >= timeout) fail("timed out after $timeout waiting for $what; last thread dump:\n$dump")
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val BUFFER_PACKAGE = "com.ditchoom.buffer."
        const val READ_BUFFER = "com.ditchoom.buffer.ReadBuffer"
        const val BASE_JVM_BUFFER = "com.ditchoom.buffer.BaseJvmBuffer"
        const val HEAP_JVM_BUFFER = "com.ditchoom.buffer.HeapJvmBuffer"
        val AWAIT_TIMEOUT: Duration = 30.seconds
    }
}
