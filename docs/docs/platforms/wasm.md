---
sidebar_position: 5
title: WASM
---

# WebAssembly Platform

Buffer on Kotlin/WASM uses native WASM linear memory for optimal performance with JavaScript interoperability.

## Implementation

| Factory | WASM Type | Use Case |
|---------|-----------|----------|
| `managed()` | `ByteArrayBuffer` | High-frequency allocations, compute-heavy workloads |
| `Default` | `LinearBuffer` | JS interop, zero-copy sharing with JavaScript |
| `shared()` | `LinearBuffer` | Same as Default |

## LinearBuffer: Native WASM Memory

`LinearBuffer` uses Kotlin/WASM's `Pointer` API to read/write directly to WASM linear memory. This provides:

- **Native instructions**: `Pointer.loadInt()`/`storeInt()` compile to single WASM instructions (`i32.load`/`i32.store`)
- **Zero-copy JS interop**: JavaScript can access the same memory via `DataView` on `wasmMemory.buffer`
- **~10-20% faster** primitive operations vs ByteArrayBuffer

:::note Performance Trade-offs
LinearBuffer's main advantage is **JavaScript interoperability**, not raw speed. For pure Kotlin operations without JS interop, ByteArrayBuffer can be faster for bulk operations since it stays in the WasmGC heap.
:::

### When to Use Each Factory

```kotlin
// Use Heap for high-frequency allocations (compute-heavy workloads)
val computeBuffer = BufferFactory.managed().allocate(1024)

// Use Default for JS interop (shares memory with JavaScript)
val interopBuffer = BufferFactory.Default.allocate(1024)
```

## Performance

Benchmark results (WASM Node.js):

| Operation | LinearBuffer (Direct) | ByteArrayBuffer (Heap) | Winner |
|-----------|----------------------|------------------------|--------|
| Primitive read/write | ~68M ops/sec | ~57M ops/sec | LinearBuffer (~1.2x) |
| Buffer-to-buffer copy | ~2.6M ops/sec | ~5.5M ops/sec | ByteArrayBuffer (~2x) |
| Allocation | Bump allocator (fast) | GC-managed | LinearBuffer |

**Key insight**: LinearBuffer is faster for primitive operations, but ByteArrayBuffer is faster for bulk operations that stay within the WasmGC heap. Choose based on your use case:
- **JS interop needed?** → Use LinearBuffer (Direct)
- **Pure Kotlin computation?** → Use ByteArrayBuffer (Heap)

## Memory Management

LinearBuffer draws from a pool of WASM linear memory that grows on demand:

- **16MB** reserved at the first allocation, growing in **16MB steps** as needed
- **256MB** ceiling on that growth, past which allocation fails with a diagnostic
- Both are configurable via `PlatformBuffer.configureWasmMemory()` /
  `LinearMemoryAllocator.configure()`, which must be called *before* the first allocation
- Use `BufferFactory.managed()` for high-frequency, short-lived allocations

The ceiling is what makes a leak reportable. Linear memory is never returned to the engine —
WebAssembly has no shrink operation — so an uncapped pool would climb until the engine gave out and
took the page down, instead of failing at a point you can debug. The default is the same 256MB the
old fixed pool imposed, so nothing that fit before fails now.

Growing is cheap in the other direction: `memory.grow` reserves address space and engines commit
physical pages on first touch, so a large *reservation* is not a large upfront footprint. What
`initialSizeMB` buys is avoiding the growth step itself, not memory you would otherwise pay for.

### Releasing is required

Linear memory is **not** garbage collected — it lives outside the Wasm-GC heap, and a `LinearBuffer`
has no finalizer that could return its bytes. Dropping the last reference to one leaks it, and the
pool it leaks from is capped. This applies to `BufferFactory.Default` just as much as to `BufferFactory.deterministic()`;
WASM is the only target where the default allocation must be released.

```kotlin
// CORRECT — the block is returned to the allocator on scope exit
BufferFactory.Default.allocate(4096).use { buffer ->
    buffer.writeInt(42)
}

// LEAKS on WASM — nothing reclaims this, and the pool is finite
val buffer = BufferFactory.Default.allocate(4096)
buffer.writeInt(42)
```

Releasing a buffer calls `LinearMemoryAllocator.free()`, which either rewinds the bump pointer (when
the block is the most recent allocation, so an allocate/use/release loop stays flat) or parks the
block on a size-classed free list for the next request of the same size.

`slice()` and `PlatformBuffer.wrapNativeAddress()` return **non-owning** views: releasing one is a
no-op, because the memory belongs to someone else. A view must not outlive its owner — once the
owner is released its block can be handed out to an unrelated allocation.

### Configuring Memory Size

```kotlin
// At app startup, BEFORE any LinearBuffer allocation:
LinearMemoryAllocator.configure(initialSizeMB = 32, maxSizeMB = 512)

// A lightweight app that should fail fast if it starts leaking:
LinearMemoryAllocator.configure(initialSizeMB = 4, maxSizeMB = 32)
```

`PlatformBuffer.configureWasmMemory()` is the public, expect/actual-friendly alias — use whichever
reads better at your call site; they configure the same values, and it is a no-op on non-WASM
targets, so it can live in common startup code:

```kotlin
PlatformBuffer.configureWasmMemory(initialSizeMB = 32, maxSizeMB = 512)
```

The single-argument `configureWasmMemory(initialSizeMB)` is **deprecated**: it still works and
leaves the ceiling at its 256MB default, but the ceiling is the half worth tuning now that the pool
grows on demand. It is a separate overload rather than a defaulted parameter, so callers compiled
against the old form keep linking.

### Usage Patterns

```kotlin
// Good: Long-lived buffer for JS interop
val wsBuffer = BufferFactory.Default.allocate(8192)

// Good: High-frequency allocations using managed factory
val managed = BufferFactory.managed()
val computeBuffer = managed.allocate(1024)
```

## JavaScript Interoperability

LinearBuffer enables zero-copy data sharing between Kotlin/WASM and JavaScript:

```kotlin
// Kotlin side: allocate in linear memory and get offset for JS
val buffer = BufferFactory.Default.allocate(1024) as LinearBuffer
buffer.writeInt(42)
buffer.writeString("Hello from WASM")

// Pass this offset to JavaScript
val jsOffset = buffer.linearMemoryOffset  // current read/write position offset
```

```javascript
// JavaScript side: access same memory using the offset from Kotlin
const wasmMemory = wasmExports.memory;
const view = new DataView(wasmMemory.buffer, jsOffset, 1024);
const value = view.getInt32(0, false); // 42 - same bytes, zero copy!
```

LinearBuffer also provides helper methods for JS array interop:

```kotlin
// Write from JS Int8Array to buffer
linearBuffer.writeFromJsArray(jsInt8Array, srcOffset = 0, length = 100)

// Read from buffer to JS Int8Array
linearBuffer.readToJsArray(jsInt8Array, dstOffset = 0, length = 100)
```

## Known Limitations

### Optimizer Bug Workaround

A Kotlin/WASM production optimizer bug makes a `@JsFun` call blow the stack when it appears
**directly in the body of** `LinearMemoryAllocator.allocateOffset`. It does not extend to a call
behind a cold branch in a separate function, which is how both the initial reservation and each
growth step reach `memory.grow`. The practical consequence is a constraint on how that code is
written, not on what the allocator can do.

If you exhaust the configured ceiling, you get an `OutOfMemoryError` with guidance:

```
LinearBuffer allocation of 4096 bytes failed: the linear-memory pool is at 256MB of the 256MB
limit. Release LinearBuffers with use { } / freeNativeMemory() — linear memory is not garbage
collected, so an unreleased buffer is leaked for the life of the process. Use
BufferFactory.managed() for high-frequency allocations, or raise the limit with
PlatformBuffer.configureWasmMemory(initialSizeMB, maxSizeMB) before the first allocation.
```

Reaching this almost always means buffers are not being released, rather than that the ceiling is
genuinely too low — see [Releasing is required](#releasing-is-required).

### ByteArray Conversion

Converting between `LinearBuffer` and Kotlin `ByteArray` requires a copy (they live in different memory spaces - linear memory vs WasmGC heap).

### Cross-Module Memory

Each WASM module has its own isolated linear memory. Passing buffers between different WASM modules (e.g., Kotlin buffer to a compression WASM module) requires copying:

```
Kotlin/WASM Module    SSL WASM Module     Compression Module
   [Memory A]    ──COPY──>  [Memory B]   ──COPY──>  [Memory C]
```

**Workarounds:**
- Use JS as intermediary (create `Uint8Array` view, pass to other module)
- Some libraries accept `Uint8Array` input, allowing a view over LinearBuffer's memory
- Future: WASM Component Model may enable shared memory regions

## Usage

```kotlin
// Standard usage - API is identical to other platforms
val buffer = BufferFactory.Default.allocate(1024)
buffer.writeInt(42)
buffer.writeLong(123456789L)
buffer.writeString("Hello WASM")

buffer.resetForRead()
val i = buffer.readInt()
val l = buffer.readLong()
val s = buffer.readString(10)
```

## Native Data Conversion

Convert buffers to WASM-native `LinearBuffer` for JavaScript interop:

```kotlin
val buffer = BufferFactory.Default.allocate(1024)
buffer.writeBytes(data)
buffer.resetForRead()

// Get LinearBuffer (zero-copy slice)
val nativeData = buffer.toNativeData()
val linearBuffer: LinearBuffer = nativeData.linearBuffer

// Access memory offset for JS interop
val offset = linearBuffer.nativeAddress
```

### Zero-Copy Behavior

| Conversion | ByteArrayBuffer (Heap) | LinearBuffer (Direct) |
|------------|------------------------|----------------------|
| `toNativeData()` | Copy (different memory) | Zero-copy (slice) |
| `toMutableNativeData()` | Copy (different memory) | Zero-copy (view) |
| `toByteArray()` | Zero-copy (backing array) | Copy (different memory) |

:::note Memory Spaces
WASM has two memory spaces: WasmGC heap (where `ByteArray` lives) and linear memory (where `LinearBuffer` lives). Conversions between these always require a copy.
:::

### JavaScript Interop with Native Data

```kotlin
// Kotlin side
val buffer = BufferFactory.Default.allocate(1024) as LinearBuffer
buffer.writeInt(42)
buffer.writeString("Hello from WASM")
buffer.resetForRead()

val nativeData = buffer.toNativeData()
val offset = nativeData.linearBuffer.nativeAddress
```

```javascript
// JavaScript side - access same memory
const view = new DataView(wasmExports.memory.buffer, offset, 1024);
const value = view.getInt32(0, false); // 42 - zero copy!
```

See [Platform Interop](../recipes/platform-interop) for more details.

## Best Practices

1. **Use Direct for JS interop** - Zero-copy sharing with JavaScript via `wasmMemory.buffer`
2. **Use Heap for pure Kotlin workloads** - ByteArrayBuffer is faster for bulk operations and has no memory limit concerns
3. **Pool buffers** - Reduces allocation overhead for both types
4. **Reuse buffers** - Call `resetForWrite()` instead of allocating new buffers
5. **Consider memory boundaries** - Crossing between WasmGC heap and linear memory has overhead
