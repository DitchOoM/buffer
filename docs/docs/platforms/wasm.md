---
sidebar_position: 5
title: WASM
---

# WebAssembly Platform

Buffer on Kotlin/WASM uses native WASM linear memory for optimal performance with JavaScript interoperability.

## Implementation

| Zone | WASM Type | Use Case |
|------|-----------|----------|
| `Heap` | `ByteArrayBuffer` | High-frequency allocations, compute-heavy workloads |
| `Direct` | `LinearBuffer` | JS interop, zero-copy sharing with JavaScript |
| `SharedMemory` | `LinearBuffer` | Same as Direct |

## LinearBuffer: Native WASM Memory

`LinearBuffer` uses Kotlin/WASM's `Pointer` API to read/write directly to WASM linear memory. This provides:

- **Native speed**: `Pointer.loadInt()`/`storeInt()` compile to single WASM instructions (`i32.load`/`i32.store`)
- **Zero-copy JS interop**: JavaScript can access the same memory via `DataView` on `wasmMemory.buffer`
- **~25% faster** single operations vs ByteArrayBuffer
- **~2x faster** bulk operations vs ByteArrayBuffer

### When to Use Each Zone

```kotlin
// Use Heap for high-frequency allocations (compute-heavy workloads)
val computeBuffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)

// Use Direct for JS interop (shares memory with JavaScript)
val interopBuffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
```

## Performance

Benchmark results (WASM production builds):

| Operation | LinearBuffer (Direct) | ByteArrayBuffer (Heap) | Speedup |
|-----------|----------------------|------------------------|---------|
| Single int write/read | 91.1M ops/sec | 73.2M ops/sec | 1.24x |
| Bulk ops (256 ints) | 2.0M ops/sec | 967K ops/sec | 2.04x |
| Allocation | 95.3M ops/sec | 22.8M ops/sec | 4.2x |

## Memory Management

LinearBuffer uses a bump allocator with pre-allocated memory:

- **16MB** allocated by default at first allocation
- Configurable via `LinearMemoryAllocator.configure()`
- Memory is not freed (bump allocator)
- Best for buffers with longer lifetimes (interop scenarios)
- Use `AllocationZone.Heap` for high-frequency short-lived allocations

### Configuring Memory Size

```kotlin
// At app startup, BEFORE any LinearBuffer allocation:
LinearMemoryAllocator.configure(initialSizeMB = 32)  // Set to 32MB

// Or use a smaller size for lightweight apps:
LinearMemoryAllocator.configure(initialSizeMB = 4)   // Set to 4MB
```

### Usage Patterns

```kotlin
// Good: Long-lived buffer for JS interop
val wsBuffer = PlatformBuffer.allocate(8192, AllocationZone.Direct)

// Good: High-frequency allocations
pool.withBuffer(1024, AllocationZone.Heap) { buffer ->
    // Process data
}
```

## JavaScript Interoperability

LinearBuffer enables zero-copy data sharing between Kotlin/WASM and JavaScript:

```kotlin
// Kotlin side: allocate in linear memory and get offset for JS
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct) as LinearBuffer
buffer.writeInt(42)
buffer.writeString("Hello from WASM")

// Pass this offset to JavaScript
val jsOffset = buffer.linearMemoryOffset  // or buffer.baseOffset for start of buffer
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

Due to a Kotlin/WASM production optimizer bug, LinearBuffer pre-allocates memory at initialization rather than growing dynamically. This means:

1. **Configurable limit** - Default 16MB, adjustable via `configureWasmMemory()`
2. **No memory reclamation** - Bump allocator doesn't free memory
3. **Use Heap for benchmarks** - High-frequency allocation benchmarks should use `AllocationZone.Heap`

If you exceed the configured limit, you'll get an `OutOfMemoryError` with guidance:

```
LinearBuffer allocation exceeded 16MB pre-allocated memory.
Call LinearMemoryAllocator.configure(initialSizeMB = N) at startup with a larger value,
or use AllocationZone.Heap for high-frequency allocation.
```

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
val buffer = PlatformBuffer.allocate(1024)
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
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
buffer.writeBytes(data)
buffer.resetForRead()

// Get LinearBuffer (zero-copy slice)
val nativeData = buffer.toNativeData()
val linearBuffer: LinearBuffer = nativeData.linearBuffer

// Access memory offset for JS interop
val offset = linearBuffer.baseOffset
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
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct) as LinearBuffer
buffer.writeInt(42)
buffer.writeString("Hello from WASM")
buffer.resetForRead()

val nativeData = buffer.toNativeData()
val offset = nativeData.linearBuffer.baseOffset
```

```javascript
// JavaScript side - access same memory
const view = new DataView(wasmExports.memory.buffer, offset, 1024);
const value = view.getInt32(0, false); // 42 - zero copy!
```

See [Platform Interop](../recipes/platform-interop) for more details.

## Best Practices

1. **Use Heap for compute-heavy workloads** - ByteArrayBuffer has no memory limit concerns
2. **Use Direct for JS interop** - Zero-copy sharing with JavaScript
3. **Pool buffers** - Reduces allocation overhead
4. **Batch operations** - Bulk reads/writes are 2x faster than single operations
5. **Reuse buffers** - Call `resetForWrite()` instead of allocating new buffers
