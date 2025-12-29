---
sidebar_position: 2
title: Buffer Pooling
---

# Buffer Pooling

Buffer pooling minimizes allocation overhead in performance-critical code like network I/O and protocol parsing.

## Why Pool Buffers?

Without pooling:
```
Request 1: allocate → use → GC
Request 2: allocate → use → GC
Request 3: allocate → use → GC
...
```

With pooling:
```
Request 1: acquire → use → release (back to pool)
Request 2: acquire (reuse!) → use → release
Request 3: acquire (reuse!) → use → release
...
```

Benefits:
- Fewer allocations
- Less GC pressure
- More predictable latency
- Better cache locality

## Creating a Pool

```kotlin
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode

val pool = BufferPool(
    threadingMode = ThreadingMode.SingleThreaded,  // or MultiThreaded
    maxPoolSize = 64,
    defaultBufferSize = 8 * 1024,  // 8KB
    byteOrder = ByteOrder.BIG_ENDIAN,
    allocationZone = AllocationZone.Direct
)
```

## Threading Modes

### SingleThreaded

Fastest option when pool access is confined to one thread:

```kotlin
val pool = BufferPool(threadingMode = ThreadingMode.SingleThreaded)
```

Use when:
- Single-threaded event loop
- Coroutine with single dispatcher
- Thread-confined access

### MultiThreaded

Lock-free implementation for concurrent access:

```kotlin
val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
```

Use when:
- Multiple threads acquire/release
- Acquire on I/O thread, release from processing thread
- Shared pool across coroutine dispatchers

## Recommended Patterns

### withBuffer (Preferred)

Automatic acquire and release:

```kotlin
pool.withBuffer(1024) { buffer ->
    buffer.writeInt(42)
    buffer.writeString("Hello")
    buffer.resetForRead()

    // Process buffer...
    sendToNetwork(buffer)
}  // Automatically released
```

### withPool (Scoped Lifetime)

Pool created and cleaned up automatically:

```kotlin
import com.ditchoom.buffer.pool.withPool

withPool(defaultBufferSize = 8192) { pool ->
    // Use pool for multiple operations
    repeat(1000) {
        pool.withBuffer { buffer ->
            processRequest(buffer)
        }
    }
}  // Pool cleared, all buffers released
```

### Manual Acquire/Release

When buffer lifetime spans multiple scopes:

```kotlin
val buffer = pool.acquire(1024)
try {
    buffer.writeInt(42)
    // ... use buffer ...
} finally {
    buffer.release()  // Always release!
}
```

## Pool Lifecycle

![Buffer Pool Lifecycle](/img/pool-lifecycle.svg)

## Pool Statistics

Monitor pool efficiency:

```kotlin
val stats = pool.stats()
println("Pool hits: ${stats.poolHits}")      // Reused from pool
println("Pool misses: ${stats.poolMisses}")  // Needed new allocation
println("Peak size: ${stats.peakPoolSize}")  // Max buffers in pool
```

## Size Matching

Pools return buffers of at least the requested size:

```kotlin
// Request 100 bytes
val buffer = pool.acquire(100)
// May receive a 1024-byte buffer from pool

// Always use the actual capacity if needed
println(buffer.capacity)  // Might be > 100
```

## Cleaning Up

```kotlin
// Clear all pooled buffers
pool.clear()

// After clear, pool is empty but still usable
```

## Complete Example: Network Server

```kotlin
class NetworkServer {
    private val pool = BufferPool(
        threadingMode = ThreadingMode.MultiThreaded,
        maxPoolSize = 128,
        defaultBufferSize = 4096
    )

    suspend fun handleConnection(socket: Socket) {
        pool.withBuffer(4096) { readBuffer ->
            // Read from socket
            val bytesRead = socket.read(readBuffer)
            readBuffer.resetForRead()

            // Parse request
            val request = parseRequest(readBuffer)

            // Generate response
            pool.withBuffer(4096) { writeBuffer ->
                serializeResponse(response, writeBuffer)
                writeBuffer.resetForRead()
                socket.write(writeBuffer)
            }
        }
    }

    fun shutdown() {
        pool.clear()
    }
}
```

## Best Practices

1. **Use withBuffer** - automatic cleanup prevents leaks
2. **Match threading mode** to your access pattern
3. **Size appropriately** - too large wastes memory, too small defeats pooling
4. **Clear on shutdown** - release resources when done
5. **Monitor stats** - tune maxPoolSize based on actual usage
