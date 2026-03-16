---
sidebar_position: 3
title: Migration Guide (v3 → v4)
---

# Migration Guide

This guide covers breaking changes from buffer v3.x to v4.x. The v4 release modernizes the API around three themes: **factory-based allocation**, **deterministic memory management**, and **zero-copy platform interop**.

## Why v4?

The v3 API grew organically and accumulated design debt:

- **`AllocationZone` enum** couldn't be extended — adding a new allocation strategy required modifying the library itself
- **`Buffer` interface** sat between `ReadBuffer`/`WriteBuffer` and `PlatformBuffer` but added no value — just noise in the type hierarchy
- **`ScopedBuffer`** mixed coroutine concerns (suspend closeable) with buffer lifecycle, making it hard to use in non-coroutine code
- **No deterministic cleanup** — JVM direct buffers relied on GC finalization, causing OOM under allocation pressure

v4 fixes all of these with a cleaner, more composable API.

## Quick Reference

| v3 (old) | v4 (new) |
|----------|----------|
| `AllocationZone.Direct` | `BufferFactory.Default` |
| `AllocationZone.Heap` | `BufferFactory.managed()` |
| `AllocationZone.SharedMemory` | `BufferFactory.shared()` |
| `PlatformBuffer.allocate(size, zone)` | `BufferFactory.Default.allocate(size)` |
| `PlatformBuffer.allocate(size, AllocationZone.Heap)` | `BufferFactory.managed().allocate(size)` |
| `Buffer` interface | Removed — use `PlatformBuffer` |
| `ScopedBuffer` / `SuspendCloseable` | `CloseableBuffer` with `.use {}` |
| `buffer as? DirectJvmBuffer` | `buffer.nativeMemoryAccess` / `buffer.unwrapFully()` |
| Manual `ByteBuffer.allocateDirect()` | `BufferFactory.Deterministic.allocate()` |

## Allocation

### Before (v3)

```kotlin
// Direct memory
val buf = PlatformBuffer.allocate(1024, AllocationZone.Direct)

// Heap memory
val buf = PlatformBuffer.allocate(1024, AllocationZone.Heap)

// Shared memory (Android IPC)
val buf = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)

// Custom zone (rare)
val buf = PlatformBuffer.allocate(1024, object : AllocationZone.Custom {
    override fun allocate(size: Int): PlatformBuffer = ...
})
```

### After (v4)

```kotlin
// Direct/native memory (platform-optimal default)
val buf = BufferFactory.Default.allocate(1024)

// Heap memory
val buf = BufferFactory.managed().allocate(1024)

// Shared memory (Android IPC)
val buf = BufferFactory.shared().allocate(1024)

// Custom factory — just implement the interface
class MyFactory : BufferFactory {
    override fun allocate(size: Int, byteOrder: ByteOrder) = ...
}
```

**Why**: `BufferFactory` is an interface, not an enum. You can implement it, compose it (`.withPooling(pool)`), and pass it around as a dependency.

## Buffer Interface Removal

### Before (v3)

```kotlin
// Buffer interface existed between ReadBuffer/WriteBuffer and PlatformBuffer
fun process(buffer: Buffer) { ... }
```

### After (v4)

```kotlin
// Use PlatformBuffer directly, or ReadBuffer/WriteBuffer for read/write-only access
fun process(buffer: PlatformBuffer) { ... }
fun readFrom(buffer: ReadBuffer) { ... }
fun writeTo(buffer: WriteBuffer) { ... }
```

**Why**: The `Buffer` interface added no methods beyond what `ReadBuffer` + `WriteBuffer` already provided. Removing it simplifies the type hierarchy.

## Deterministic Memory

### Before (v3)

```kotlin
// No built-in deterministic cleanup — relied on GC
val buf = PlatformBuffer.allocate(1024, AllocationZone.Direct)
// Hope the GC collects it before OOM...
```

### After (v4)

```kotlin
// Explicit cleanup with CloseableBuffer
val buf = BufferFactory.Deterministic.allocate(1024)
buf.use {
    it.writeInt(42)
    it.resetForRead()
    it.readInt()
} // Memory freed here, guaranteed

// Or with a scope for multiple buffers
withScope { scope ->
    val a = scope.allocate(4096)
    val b = scope.allocate(8192)
    // ... use buffers ...
} // Both freed here
```

**Why**: High-throughput I/O code (network servers, file processing) can't afford to wait for GC finalization of direct ByteBuffers. `CloseableBuffer` gives you C++-style RAII with Kotlin's `.use {}`.

## Platform Interop (NativeData)

### Before (v3)

```kotlin
// Casting and platform checks
val jvmBuf = buffer as? DirectJvmBuffer
val byteBuffer: ByteBuffer = jvmBuf?.byteBuffer ?: ...
```

### After (v4)

```kotlin
// Uniform API across all platforms
val nativeData = buffer.toNativeData()       // Read-only native handle
val mutableData = buffer.toMutableNativeData() // Mutable native handle
val bytes = buffer.toByteArray()             // Managed memory (ByteArray)

// Platform-specific access through the wrapper
val byteBuffer: ByteBuffer = nativeData.byteBuffer     // JVM/Android
val nsData: NSData = nativeData.nsData                  // Apple
val arrayBuffer: ArrayBuffer = nativeData.arrayBuffer   // JS
val linearBuffer: LinearBuffer = nativeData.linearBuffer // WASM
val nativeBuffer: NativeBuffer = nativeData.nativeBuffer // Linux
```

**Why**: The old approach required knowing the concrete buffer type. `toNativeData()` works on any buffer — if it's already native memory, it's zero-copy; if not, it copies once. No casting, no platform checks in your code.

## ScopedBuffer / SuspendCloseable

### Before (v3)

```kotlin
// SuspendCloseable required coroutine context
suspend fun process() {
    val scoped = ScopedBuffer.allocate(1024)
    try {
        // ... use buffer ...
    } finally {
        scoped.close() // suspend function
    }
}
```

### After (v4)

```kotlin
// CloseableBuffer works anywhere — no coroutines required
fun process() {
    BufferFactory.Deterministic.allocate(1024).use { buffer ->
        buffer.writeInt(42)
        buffer.resetForRead()
        buffer.readInt()
    }
}
```

**Why**: Tying buffer lifecycle to coroutines was unnecessary. Most buffer usage is synchronous. `CloseableBuffer` uses Kotlin's standard `Closeable` pattern.

## Buffer Pooling

### Before (v3)

No built-in pooling — you had to roll your own or use a third-party pool.

### After (v4)

```kotlin
// Built-in high-performance pool
val pool = BufferPool.SingleThreaded(defaultSize = 8192)

pool.withBuffer(1024) { buffer ->
    buffer.writeInt(42)
    // buffer automatically returned to pool
}

// Or compose with any factory
val pooledFactory = BufferFactory.Default.withPooling(pool)
val buf = pooledFactory.allocate(1024) // From pool if available
```

## Search and Comparison

New in v4 — no migration needed, just start using:

```kotlin
// Find values in buffers (SIMD-accelerated on native)
val pos = buffer.indexOf(0x42.toByte())
val pos = buffer.indexOf("needle")
val pos = buffer.indexOf(otherBuffer)

// Compare buffers
buffer1.contentEquals(buffer2)
val diffAt = buffer1.mismatch(buffer2)

// Fill and mask
buffer.fill(0x00.toByte())
buffer.xorMask(0x12345678) // WebSocket frame masking
```

## Wrapper Transparency

If you have code that casts buffers to platform types, update it:

### Before (v3)

```kotlin
// Fragile — breaks on wrapped buffers
val direct = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
if (direct is DirectJvmBuffer) { ... }
```

### After (v4)

```kotlin
// Safe — works through any number of wrapper layers
val actual = buffer.unwrapFully()
if (actual is DirectJvmBuffer) { ... }

// Or use the extension properties (recommended)
val nma = buffer.nativeMemoryAccess   // NativeMemoryAccess?
val mma = buffer.managedMemoryAccess  // ManagedMemoryAccess?
```

## Checklist

- [ ] Replace `AllocationZone.Direct` → `BufferFactory.Default`
- [ ] Replace `AllocationZone.Heap` → `BufferFactory.managed()`
- [ ] Replace `AllocationZone.SharedMemory` → `BufferFactory.shared()`
- [ ] Replace `PlatformBuffer.allocate(size, zone)` → `factory.allocate(size)`
- [ ] Remove `Buffer` interface usage → use `PlatformBuffer`, `ReadBuffer`, or `WriteBuffer`
- [ ] Replace `ScopedBuffer`/`SuspendCloseable` → `CloseableBuffer` + `.use {}`
- [ ] Replace platform casts → `toNativeData()` / `unwrapFully()` / `.nativeMemoryAccess`
- [ ] Consider buffer pooling for high-throughput paths
