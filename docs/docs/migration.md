---
sidebar_position: 3
title: Migration Guide
---

# Migration Guide

This guide covers breaking changes between major releases. The [v4 → v5](#v4-v5)
section is newest; the v3 → v4 guide follows below.

## v4 → v5 {#v4-v5}

v5 reworks the `buffer-codec` framing API and unifies buffer bounds-checking across
platforms. The codec changes are **source-breaking** for code that consumed
`buffer-codec` 4.2.x. The core `buffer` module has no breaking API changes — only new
portable behavior (see [Portable overflow exceptions](#portable-exceptions)).

If you do not use `buffer-codec`, the only thing to know is that
`BufferOverflowException` / `BufferUnderflowException` are now common types you can
`catch` in `commonMain`.

### Codec quick reference

| v4 (old) | v5 (new) |
|----------|----------|
| `Codec<T>` (single interface) | `Codec<T>` = `Encoder<in T>` + `Decoder<out T>` + `FrameDetector` |
| `fun sizeOf(value): SizeEstimate` | `fun wireSize(value, context): WireSize` |
| `SizeEstimate.Exact` / `.UnableToPrecalculate` | `WireSize.Exact(bytes)` / `WireSize.BackPatch` |
| `PeekResult` in `com.ditchoom.buffer.stream` | `PeekResult` in `com.ditchoom.buffer.codec` |
| `PeekResult.Size(n)` | `PeekResult.Complete(n)` |
| `CodecContext.Key<T>` | `DecodeKey<T>` / `EncodeKey<T>` / `CodecKey<T>` |
| `@WhenTrue` | `@When` |
| `context.getOrDefault(key, default)` | `context[key] ?: default` |

### `Codec` split into directional interfaces

`Codec<T>` is now the intersection of three interfaces — `Encoder<in T>`,
`Decoder<out T>`, and `FrameDetector`. A type that declares `Codec<T>` and implements
`encode` / `decode` keeps compiling unchanged. The benefit: send-only or receive-only
code can now depend on just `Encoder<T>` or `Decoder<T>` instead of the full codec.

### `sizeOf` → `wireSize`

`Encoder.sizeOf` is replaced by `Encoder.wireSize`, and the `SizeEstimate` sealed type
is removed.

**Before (v4)**

```kotlin
override fun sizeOf(value: MyMessage): SizeEstimate =
    SizeEstimate.Exact(4 + value.payload.length)
```

**After (v5)**

```kotlin
override fun wireSize(value: MyMessage, context: EncodeContext): WireSize =
    WireSize.Exact(4 + value.payload.length)
```

Return `WireSize.Exact(n)` for fixed-size codecs. For variable-length codecs that
cannot precompute the size, return `WireSize.BackPatch` — the framework encodes into a
`GrowableWriteBuffer` and back-patches length prefixes afterward (`WireSize.BackPatch`
replaces `SizeEstimate.UnableToPrecalculate`). Generated codecs handle this for you.

### `PeekResult` moved and reshaped

`PeekResult` moved from `com.ditchoom.buffer.stream` to `com.ditchoom.buffer.codec`,
and its variants changed:

- `PeekResult.Size(n)` → `PeekResult.Complete(n)`
- `NeedsMoreData` — unchanged
- `NoFraming` — new. A codec at a streaming boundary that cannot frame now fails
  loudly at startup instead of silently hanging the receive loop.

Update the import and replace any `PeekResult.Size(n)` with `PeekResult.Complete(n)`.

### Context keys are now directional

The single nested `CodecContext.Key<T>` is split into `DecodeKey<T>`, `EncodeKey<T>`,
and `CodecKey<T>` (the last extends both). `DecodeContext.with` / `EncodeContext.with`
take the matching directional key.

**Before (v4)**

```kotlin
object MaxSizeKey : CodecContext.Key<Int>
```

**After (v5)**

```kotlin
// readable during decode only
object MaxSizeKey : DecodeKey<Int>

// available to both directions
object AllocatorKey : CodecKey<BufferFactory>
```

### `@WhenTrue` → `@When`

Renamed; semantics unchanged. Rename the annotation at every use site.

### `@WireOrder` now overrides buffer byte order

A field annotated `@WireOrder(Endianness.Little)` is now encoded/decoded little-endian
**regardless** of the `ReadBuffer`/`WriteBuffer` byte order. Previously the buffer's
`byteOrder` could leak through for some scalar shapes (issue #154). If you compensated
for the old bug by flipping `byteOrder` globally, that workaround will now
double-correct — remove it.

### `getOrDefault` removed

`DecodeContext.getOrDefault` / `EncodeContext.getOrDefault` are removed. Use
`context[key] ?: default` at the call site.

### `buffer-flow` bridge API removed

The unused bridge API is gone: `Connection.asByteStream`, `ByteStream.asCodecConnection`,
`ByteStream.asFramedCodecConnection`, `Sender.contramap`, `Receiver.map`,
`Receiver.mapNotNull`, and `Connection.map`. The dual-direction
`Connection.mapNotNull(encode, decode)` used for protocol layering is **retained** —
see [Flow & Connections](./recipes/flow).

### Portable overflow / underflow exceptions {#portable-exceptions}

`BufferOverflowException` / `BufferUnderflowException` are now common `expect class`
types. On JVM/Android the actuals still subclass `java.nio.BufferOverflowException` /
`java.nio.BufferUnderflowException`, so existing `catch (e: java.nio.BufferOverflowException)`
keeps matching. Non-JVM targets previously threw platform-native exceptions (or, on
Apple/Linux native, segfaulted on overflow) — they now throw the common type.
`commonMain` code can now portably `catch (e: BufferOverflowException)`. No migration
needed; this change is strictly additive.

### v5 checklist

- [ ] Implement `wireSize` instead of `sizeOf`; replace `SizeEstimate` with `WireSize`
- [ ] Update `PeekResult` import to `com.ditchoom.buffer.codec`; `Size(n)` → `Complete(n)`
- [ ] Replace `CodecContext.Key<T>` with `DecodeKey` / `EncodeKey` / `CodecKey`
- [ ] Rename `@WhenTrue` → `@When`
- [ ] Remove any global `byteOrder` workaround for `@WireOrder` fields
- [ ] Replace `context.getOrDefault(key, default)` → `context[key] ?: default`
- [ ] Remove use of deleted `buffer-flow` bridge functions

---

## v3 → v4

The v4 release modernizes the API around three themes: **factory-based allocation**, **deterministic memory management**, and **zero-copy platform interop**.

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
| Manual `ByteBuffer.allocateDirect()` | `BufferFactory.deterministic().allocate()` |

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
val buf = BufferFactory.deterministic().allocate(1024)
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
    BufferFactory.deterministic().allocate(1024).use { buffer ->
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
