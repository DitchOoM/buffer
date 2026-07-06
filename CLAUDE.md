# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ByteBuffer is a Kotlin Multiplatform library providing platform-agnostic byte buffer management with an API similar to Java's ByteBuffer. It delegates to native implementations on each platform to avoid memory copies.

**Package:** `com.ditchoom.buffer`

## Build Commands

```bash
# Build for all platforms
./gradlew build

# Run tests
./gradlew allTests                # Run tests for all platforms (aggregated report)
./gradlew check                   # Run all checks (tests + linting)
./gradlew test                    # Common/JVM tests
./gradlew connectedCheck          # Android instrumented tests (requires emulator)

# Linting
./gradlew ktlintCheck             # Check code style
./gradlew ktlintFormat            # Auto-format code

# Run specific test class
./gradlew :jvmTest --tests "com.ditchoom.buffer.BufferTests"
./gradlew :jsNodeTest --tests "com.ditchoom.buffer.BufferTests"

# Codec processor tests (KSP compile-time validation)
./gradlew :buffer-codec-processor:test

# Codec integration tests (protocol round-trips: TLS, PNG, RIFF, WebSocket, MQTT)
./gradlew :buffer-codec-test:jvmTest
```

## Architecture

### Kotlin Multiplatform Structure

The project uses the expect/actual pattern with platform-specific implementations:

```
src/
├── commonMain/          # Shared interfaces (PlatformBuffer, ReadBuffer, WriteBuffer)
├── commonTest/          # Shared tests run on all platforms
├── jvmCommonMain/       # Shared JVM/Android: BaseJvmBuffer, CharsetEncoderHelper
├── jvmMain/             # JVM: HeapJvmBuffer, DirectJvmBuffer, JvmBuffer
├── androidMain/         # Android: extends JVM + SharedMemory/Parcelable IPC
├── appleMain/           # iOS/macOS/watchOS/tvOS: MutableDataBuffer (NSMutableData)
├── jsMain/              # Browser/Node.js: JsBuffer (Int8Array, SharedArrayBuffer support)
├── wasmJsMain/          # WASM: LinearBuffer (native memory) + ByteArrayBuffer (heap)
├── nonJvmMain/          # Shared native/WASM: ByteArrayBuffer
└── nativeMain/          # Linux/Apple native: uses nonJvmMain
```

### Buffer Types by Platform

| Platform | Heap (wrap/Heap zone) | Direct (allocate) | Shared Memory |
|----------|----------------------|-------------------|---------------|
| JVM | `HeapJvmBuffer` | `DirectJvmBuffer` | Falls back to Direct |
| Android | `HeapJvmBuffer` | `DirectJvmBuffer` | `ParcelableSharedMemoryBuffer` |
| Apple | `ByteArrayBuffer` | `MutableDataBuffer` | Falls back to Direct |
| JS | `JsBuffer` | `JsBuffer` | `JsBuffer` (SharedArrayBuffer) |
| WASM | `ByteArrayBuffer` | `LinearBuffer` | Falls back to Direct |
| Linux | `ByteArrayBuffer` | `ByteArrayBuffer` | Falls back to Direct |

### Memory Access Interfaces

- `NativeMemoryAccess` - Direct native memory pointer (DirectJvmBuffer, MutableDataBuffer, LinearBuffer, JsBuffer, NativeBuffer)
- `ManagedMemoryAccess` - Kotlin ByteArray backing (HeapJvmBuffer, ByteArrayBuffer, JsBuffer)
- `SharedMemoryAccess` - Cross-process shared memory (ParcelableSharedMemoryBuffer, JsBuffer with SharedArrayBuffer)

### Native Data Conversions

Convert buffers to platform-native types for interop with platform APIs:

```kotlin
// Get native memory handle (returns NativeData wrapper)
val nativeData: NativeData = buffer.toNativeData()

// Get mutable native memory handle (returns MutableNativeData wrapper)
val mutableData: MutableNativeData = buffer.toMutableNativeData()

// Get managed memory (guarantees ManagedMemoryAccess)
val bytes = buffer.toByteArray()
```

**Accessing platform-specific types:**

```kotlin
// JVM/Android
val byteBuffer: ByteBuffer = buffer.toNativeData().byteBuffer

// Apple
val nsData: NSData = buffer.toNativeData().nsData

// JS
val arrayBuffer: ArrayBuffer = buffer.toNativeData().arrayBuffer
val int8Array: Int8Array = buffer.toMutableNativeData().int8Array

// WASM
val linearBuffer: LinearBuffer = buffer.toNativeData().linearBuffer

// Linux
val nativeBuffer: NativeBuffer = buffer.toNativeData().nativeBuffer
```

**Mental model:**
- `toNativeData()` / `toMutableNativeData()` → guarantees native memory (direct ByteBuffer, NSData, NativeBuffer, etc.)
- `toByteArray()` → guarantees managed memory (Kotlin ByteArray)

**Zero-copy vs Copy:**
- Zero-copy when source already matches target type (e.g., direct buffer → direct ByteBuffer)
- Copies when conversion is needed (e.g., heap buffer → direct ByteBuffer)

**Platform wrapper contents:**

| Platform | `NativeData` contains | `MutableNativeData` contains | `toByteArray()` |
|----------|----------------------|------------------------------|-----------------|
| JVM | `ByteBuffer` (direct, read-only) | `ByteBuffer` (direct) | `ByteArray` |
| Android | `ByteBuffer` (direct, read-only) | `ByteBuffer` (direct) | `ByteArray` |
| Apple | `NSData` | `NSMutableData` | `ByteArray` |
| JS | `ArrayBuffer` | `Int8Array` | `ByteArray` |
| WASM | `LinearBuffer` | `LinearBuffer` | `ByteArray` |
| Linux | `NativeBuffer` | `NativeBuffer` | `ByteArray` |

**Apple-specific helpers:**

```kotlin
// Convert ByteArray to NSData/NSMutableData
val nsData = byteArray.toNSData()
val nsMutableData = byteArray.toNSMutableData()
```

### Key Interfaces

- `PlatformBuffer` - Main buffer interface combining read/write operations
- `ReadBuffer` - Read operations (relative and absolute)
- `WriteBuffer` - Write operations (relative and absolute)
- `BufferFactory` - Memory allocation strategy: `Default` (native), `managed()` (heap), `shared()` (IPC), `deterministic()` (explicit cleanup)

### Deterministic Memory (`com.ditchoom.buffer`)

`BufferFactory.deterministic()` returns buffers that implement `CloseableBuffer` for
guaranteed resource cleanup independent of garbage collection. Pair it with the
`PlatformBuffer.use { }` extension (or call `freeNativeMemory()` explicitly) so native
memory is released immediately rather than waiting on GC:

```kotlin
BufferFactory.deterministic().allocate(8192).use { buffer ->
    buffer.writeInt(42)
    buffer.resetForRead()
    val value = buffer.readInt()
} // freed immediately when the block exits, no GC needed
```

`deterministic(threadConfined: Boolean = false)` takes an optional `threadConfined`
parameter: on JVM 21+ it selects `Arena.ofConfined()` instead of the default
`Arena.ofShared()`; it is ignored on every other platform.

**Platform Implementations:**

| Platform | Implementation | Allocation |
|----------|---------------|------------|
| JVM 21+  | `FfmBuffer` | FFM `Arena.ofShared()` / `Arena.ofConfined()` |
| JVM 9-20 | `DeterministicDirectJvmBuffer` | DirectByteBuffer + `Unsafe.invokeCleaner` |
| JVM 8 / Android | `DeterministicUnsafeJvmBuffer` | `Unsafe.allocateMemory`/`freeMemory` |
| Apple    | `MutableDataBuffer` | ARC-managed (already deterministic) |
| Linux    | `NativeBuffer` | malloc/free |
| WASM     | `LinearBuffer` | Linear memory (already deterministic) |
| JS       | `JsBuffer` | GC-managed (no deterministic alternative) |

**When to use `BufferFactory.deterministic()` vs `BufferFactory.Default`/pooling:**
- Use `deterministic()` for: FFI/JNI interop, zero-copy I/O, and any path where you need
  native memory freed at a precise point instead of waiting on GC.
- Use `BufferFactory.Default` for: general-purpose, GC-managed buffering.
- Use `BufferPool` (see below) instead when you're allocating/freeing the same size
  repeatedly in a hot path — pooling amortizes allocation cost across reuses, whereas
  `deterministic()` buffers are freed for good after each `use { }` block.

`buffer.use { }` is safe to call on *any* `PlatformBuffer`, not just `CloseableBuffer`
ones: it frees native memory for deterministic buffers, returns pooled buffers to their
pool (decrementing refcount), and is a harmless no-op for GC-managed buffers and
non-owning slices.

### Buffer Comparison & Search Methods

ReadBuffer provides optimized search and comparison operations:

```kotlin
// Content comparison (SIMD-accelerated on native, optimized on JVM 11+)
buffer1.contentEquals(buffer2)  // true if remaining bytes are identical
buffer1.mismatch(buffer2)       // index of first difference, or -1

// Search for values (uses bulk Long comparisons, XOR zero-detection)
buffer.indexOf(0x42.toByte())           // find byte
buffer.indexOf(0x1234.toShort())        // find Short (respects byte order)
buffer.indexOf(0x12345678)              // find Int
buffer.indexOf(0x123456789ABCDEF0L)     // find Long
buffer.indexOf("Hello")                 // find string (UTF-8 default)
buffer.indexOf(otherBuffer)             // find byte sequence

// Aligned search (SIMD auto-vectorized on native platforms)
// Only checks naturally-aligned positions - use when data was written with writeShort/Int/Long
buffer.indexOf(0x1234.toShort(), aligned = true)  // 2-byte aligned positions only
buffer.indexOf(0x12345678, aligned = true)        // 4-byte aligned positions only
buffer.indexOf(0x123456789ABCDEF0L, aligned = true) // 8-byte aligned positions only
```

### Buffer Fill & Masking Methods

WriteBuffer provides optimized fill and masking operations:

```kotlin
// Fill remaining space with value (writes 8 bytes at a time internally)
buffer.fill(0x00.toByte())      // zero-fill
buffer.fill(0x1234.toShort())   // fill with Short pattern
buffer.fill(0x12345678)         // fill with Int pattern
buffer.fill(0x123456789ABCDEF0L) // fill with Long pattern

// XOR mask (SIMD-accelerated on native, used for WebSocket frame masking)
buffer.xorMask(0x12345678)      // XOR remaining bytes with repeating 4-byte mask
```

### Buffer Pool (`com.ditchoom.buffer.pool`)

High-performance buffer pooling for minimizing allocations:

- `BufferPool` - Main pool interface with `SingleThreaded` and `MultiThreaded` modes
- `PooledBuffer` - Buffer acquired from pool, must call `release()` when done
- `withBuffer { }` - Recommended: auto-acquires and releases buffer
- `withPool { }` - Creates pool, runs block, clears pool on exit

```kotlin
// Preferred usage pattern
withPool(defaultBufferSize = 8192) { pool ->
    pool.withBuffer(1024) { buffer ->
        buffer.writeInt(42)
    }
}
```

### Buffer Stream (`com.ditchoom.buffer.stream`)

Chunked processing for large buffers and streaming data:

- `BufferStream` - Iterates over a buffer in fixed-size chunks
- `StreamProcessor` - Handles fragmented data (e.g., network packets) with peek/read operations

```kotlin
val processor = StreamProcessor.create(pool)
processor.append(networkData)
val length = processor.readInt()

// Preferred: readBufferScoped auto-releases the buffer back to the pool
val message = processor.readBufferScoped(length) {
    MyMessage(readInt(), readString(remaining()))
}

// Alternative: readBuffer returns a buffer you must manage yourself
// Note: the returned buffer is NOT released back to the pool automatically
val payload = processor.readBuffer(length)
```

### Wrapper Transparency

Buffer wrappers (PooledBuffer, TrackedSlice) delegate to an underlying PlatformBuffer. Code that consumes buffers must work transparently through these wrappers.

**Correct pattern — interface-based dispatch:**
```kotlin
val nma = buffer.nativeMemoryAccess   // returns NativeMemoryAccess? via extension
val mma = buffer.managedMemoryAccess  // returns ManagedMemoryAccess? via extension
```

**Correct pattern — unwrap for platform-specific fast paths:**
```kotlin
val actual = buffer.unwrapFully()     // strips all wrapper layers
if (actual is JsBuffer) { /* fast path */ }
```

**Anti-pattern — NEVER do this:**
```kotlin
(buffer as? PlatformBuffer)?.unwrap() ?: buffer  // breaks on PooledBuffer/TrackedSlice
```

All new buffer-consuming code must be tested with wrapper types (see `WrapperTransparencyTests`).

### Factory Pattern

**Always use `BufferFactory` to create buffers** — never use `PlatformBuffer.allocate()` or `PlatformBuffer.wrap()` directly:
```kotlin
BufferFactory.Default.allocate(size)                    // Native memory (platform-optimal)
BufferFactory.Default.wrap(byteArray)                   // Wrap existing ByteArray (zero-copy)
BufferFactory.managed().allocate(size)                  // Heap memory (ByteArray-backed)
BufferFactory.shared().allocate(size)                   // Shared memory (Android IPC)
BufferFactory.deterministic().allocate(size)             // Explicit cleanup via .use {}
```

Library code should accept `BufferFactory` as a parameter so callers control allocation:
```kotlin
class MyProtocol(private val factory: BufferFactory = BufferFactory.Default) {
    fun encode(data: MyData): PlatformBuffer {
        val buffer = factory.allocate(data.sizeOf())
        // ...
    }
}
```

## Best Practices for Using This Library

### Always Use `BufferFactory`, Not `PlatformBuffer`

`BufferFactory` is the correct entry point for all buffer creation. Do NOT use `PlatformBuffer.allocate()` or `PlatformBuffer.wrap()` — these are legacy shortcuts that bypass factory composition (pooling, monitoring, deterministic cleanup).

```kotlin
// CORRECT
val buffer = BufferFactory.Default.allocate(1024)
val wrapped = BufferFactory.Default.wrap(byteArray)

// WRONG — do not use these
val buffer = PlatformBuffer.allocate(1024)
val wrapped = PlatformBuffer.wrap(byteArray)
```

### Use Protocol Codecs for Structured Data

For any structured binary data, use `buffer-codec` with `@ProtocolMessage` annotations instead of hand-writing `readInt()`/`writeInt()` sequences. Hand-written encode/decode is error-prone and doesn't guarantee round-trip correctness.

```kotlin
// WRONG — manual field-by-field serialization
fun encode(buffer: WriteBuffer, msg: MyMessage) {
    buffer.writeInt(msg.id)
    buffer.writeShort(msg.type)
    buffer.writeLengthPrefixedUtf8String(msg.payload)
}

// CORRECT — annotated data class, codec is generated
@ProtocolMessage
data class MyMessage(
    val id: Int,
    val type: Short,
    @LengthPrefixed val payload: String,
)
// MyMessageCodec.encode(buffer, msg) — generated, type-safe, batch-optimized
```

### Sealed Interface Dispatch with `@PacketType` and `@DispatchOn`

For protocols with a type discriminator, use sealed interfaces with `@PacketType`:

```kotlin
// Simple dispatch — reads one byte, matches against value
@ProtocolMessage
sealed interface Command {
    @ProtocolMessage @PacketType(0x01)
    data class Ping(val timestamp: Long) : Command

    @ProtocolMessage @PacketType(0x02)
    data class Echo(@LengthPrefixed val message: String) : Command
}
```

For bit-packed headers, multi-byte discriminators, or prefix-before-type formats, use `@DispatchOn` + `@DispatchValue`:

```kotlin
// Custom discriminator — extracts dispatch value from a value class
@JvmInline
@ProtocolMessage
value class MqttFixedHeader(val raw: UByte) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()
}

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface MqttPacket {
    @ProtocolMessage @PacketType(value = 1, wire = 0x10)
    data class Connect(val header: MqttFixedHeader, ...) : MqttPacket
}
```

Key rules: `@DispatchValue` must return `Int`. `wire` values are validated at compile time against the discriminator's inner type range (e.g., UByte 0-255). Duplicate `@PacketType` values are compile errors.

For protocols that mix byte orders within a single message, use `@WireOrder(Endianness.Big)` or `@WireOrder(Endianness.Little)` on individual fields. This overrides the message-level `@ProtocolMessage(wireOrder = ...)`. It composes with `@WireBytes` when a custom-width field's byte order also differs from the message default.

### `peekFrameSize` — Generated Stream Framing

Every codec automatically generates `peekFrameSize(stream: StreamProcessor, baseOffset: Int = 0): Int?` when the frame size is determinable from the wire format. This peeks at a stream to determine the total bytes needed for decode, without consuming data. Eliminates manual peek offset math in streaming loops:

```kotlin
while (stream.available() >= PacketCodec.MIN_HEADER_BYTES) {
    val frameSize = PacketCodec.peekFrameSize(stream) ?: break
    if (stream.available() < frameSize) break
    val msg = stream.readBufferScoped(frameSize) { PacketCodec.decode(this) }
}
```

Handles `@LengthPrefixed`, `@LengthFrom`, `@WhenTrue` (including value class property conditions), `@WireBytes`, sealed dispatch, `@DispatchOn` (value class and data class discriminators), nested messages, and multiple variable-length fields.

### CodecContext — Typed Runtime Configuration

Pass typed configuration through codec chains without global state:

```kotlin
val ctx = DecodeContext.Empty
    .with(MyCodec.AllocatorKey, hardwareAllocator)
    .with(MyCodec.MaxSizeKey, 1_000_000)
val result = TopLevelCodec.decode(buffer, ctx)
// context flows automatically through sealed dispatch → @UseCodec → nested codecs
```

Context is forwarded automatically by generated code through sealed dispatch, `@UseCodec` fields, and nested `@ProtocolMessage` fields.

### Avoid Unnecessary Memory Copies

**Internal-codec staging** (read a sub-region inside one decode call, discard at end):
- `slice()` / `readBytes(n)` — zero-copy views. **Aliasing contract**: the view shares storage with the parent buffer; mutations are visible in both. **MUST NOT** outlive the parent buffer's scope.
- `readByteArray(n)` — **platform-dependent**. JVM / Apple / Linux / WASM / `ByteArrayBuffer` copy into a fresh `ByteArray`; JS aliases via `Int8Array(srcBuffer, srcOffset, n)`. Use only for internal staging when you don't need cross-platform independence guarantees.

**Consumer boundary** (decode produces a value the caller retains past the buffer's scope):
- `copyToByteArray(n)` — fresh, independently-allocated heap `ByteArray`. Explicit copy contract on every platform.
- `readInto(dst, offset, length)` — scratch-array reuse. Caller supplies the `ByteArray`; bytes are bulk-copied into the requested window.
- `factory.allocate(n).also { it.write(source); it.resetForRead() }` — consumer-owned `PlatformBuffer` with the caller's allocator.

Other zero-copy / no-copy primitives:
- `toNativeData()` / `toMutableNativeData()` for platform interop instead of converting to `ByteArray` first.
- `BufferFactory.wrapNativeAddress()` to write directly into externally-owned memory (HardwareBuffer, mmap, Skia surface).
- `writeString()` directly instead of `payload.encodeToByteArray()` + `writeBytes()`.
- `BufferPool` in hot paths to reuse buffers instead of allocating per request.
- Accept `ReadBuffer` / `WriteBuffer` in function signatures, not `ByteArray`.

```kotlin
// WRONG — unnecessary copies
val bytes = buffer.readByteArray(length)  // platform-dependent: may alias on JS
val newBuffer = BufferFactory.Default.wrap(bytes)  // copies again on managed backends
processBytes(bytes)  // forces ByteArray view of data that's already in a buffer

// CORRECT — zero-copy view for internal staging
val slice = buffer.readBytes(length)
processBuffer(slice)  // works directly on the buffer; slice is discarded with parent

// CORRECT — explicit copy at the consumer boundary
val owned = buffer.copyToByteArray(length)  // safe to retain past `buffer`'s scope
return OpaqueBytes(owned)
```

### Canonical decode patterns

The buffer-codec lockdown (v1) makes the consumer boundary explicit. Three canonical patterns, ordered from preferred to fallback:

**Pattern 1 — Zero-copy decode into a typed value (preferred).**

```kotlin
@JvmInline value class Bitmap(val nativeBitmap: PlatformBitmap) : Payload
// expect/actual: PlatformBitmap = UIImage / ImageBitmap / BufferedImage / android.graphics.Bitmap

object BitmapCodec : Codec<Bitmap> {
    override fun decode(buffer: ReadBuffer, ctx: DecodeContext): Bitmap {
        val nativeData = buffer.toNativeData()    // zero-copy native handle
        return Bitmap(decodeJpegToNative(nativeData))  // platform decoder → typed value
    }
}
```

No copy. The buffer's native handle (NSData / ArrayBuffer / ByteBuffer) is handed straight to the platform's decoder; the output is a typed value decoupled from the input buffer. The `Payload` marker holds — `Bitmap.nativeBitmap` is a typed platform handle, not a raw buffer or ByteArray.

**Pattern 2 — Consumer-owned `PlatformBuffer` via existing `write(source)`.**

For consumers who want bytes in their own buffer (shared memory for IPC, deterministic-cleanup buffer for hardware FFI, etc.):

```kotlin
data class IpcBuffer(val buffer: PlatformBuffer)   // NOT Payload (contains a buffer)

object IpcBufferCodec : Codec<IpcBuffer> {
    override fun decode(buffer: ReadBuffer, ctx: DecodeContext): IpcBuffer {
        val factory = ctx[BufferFactoryKey] ?: BufferFactory.managed()
        val dst = factory.allocate(buffer.remaining())
        dst.write(buffer)              // copies bytes into consumer-owned dst
        dst.resetForRead()
        return IpcBuffer(dst)
    }
}
```

Consumer picks their factory (via `DecodeContext` key, or fixed at codec construction). `dst.write(source)` is the existing buffer-to-buffer copy primitive — Phase 0 verifies it copies (rather than aliases) on every platform. The `Payload` marker is NOT applied because `IpcBuffer` holds a buffer; the consumer steps outside the Payload abstraction.

**Pattern 3 — Consumer-owned `ByteArray` via `copyToByteArray(n)`.**

For consumers who want a heap `ByteArray` (SQL BLOB persistence, base64 encoding, debug capture):

```kotlin
data class OpaqueBytes(val bytes: ByteArray)      // NOT Payload (contains a ByteArray)

object OpaqueBytesCodec : Codec<OpaqueBytes> {
    override fun decode(buffer: ReadBuffer, ctx: DecodeContext): OpaqueBytes =
        OpaqueBytes(buffer.copyToByteArray(buffer.remaining()))
}
```

`copyToByteArray(n)` carries an explicit copy contract on every platform — guaranteed safe to retain past the buffer's scope. The verb `copy` makes the cost visible at the call site.

`readByteArray(n)` retains its existing (platform-dependent) semantics and is appropriate only for internal-codec staging where independence is not required.

### Accept Factory as a Parameter in Library Code

Library code should accept `BufferFactory` as a constructor parameter so callers control allocation strategy:

```kotlin
class ProtocolConnection(
    private val factory: BufferFactory = BufferFactory.Default,
) {
    fun send(packet: Packet) {
        factory.allocate(packet.sizeOf()).use { buffer ->
            packet.writeTo(buffer)
        }
    }
}
```

## Platform Notes

- **JVM/Android:** Direct ByteBuffers (`DirectJvmBuffer`) used by default; `HeapJvmBuffer` for `wrap()` and `BufferFactory.managed()`
- **Android ART allocator behavior** (LOS vs non-moving space routing, fragmentation OOMs, emulator repro recipe): see `ANDROID_ART_ALLOCATOR.md`
- **Android SharedMemory:** Use `BufferFactory.shared()` for zero-copy IPC via Parcelable (API 27+)
- **Apple:** `MutableDataBuffer` wraps NSMutableData (native memory); `wrap(ByteArray)` returns `ByteArrayBuffer`
- **Apple NSData interop:** Use `BufferFactory.Default.wrap(nsData)` or `BufferFactory.Default.wrap(nsMutableData)` for zero-copy Apple API interop
- **JS SharedArrayBuffer:** Requires CORS headers (`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`)
- **WASM:** `LinearBuffer` (Direct) uses native WASM memory for JS interop; `ByteArrayBuffer` (Heap) for compute workloads
- **Linux:** `NativeBuffer` (Direct) uses malloc/free for zero-copy io_uring I/O; `ByteArrayBuffer` (Heap) for managed memory

## Benchmarking

### Running Benchmarks

```bash
# All platforms (kotlinx-benchmark)
./gradlew benchmark

# Platform-specific
./gradlew jvmBenchmarkBenchmark
./gradlew jsBenchmarkBenchmark
./gradlew wasmJsBenchmarkBenchmark
./gradlew macosArm64BenchmarkBenchmark

# Quick validation (single iteration)
./gradlew quickBenchmark

# Android (requires device/emulator)
./gradlew connectedBenchmarkAndroidTest
```

### Benchmark Source Locations

- `src/commonBenchmark/kotlin/` - Shared benchmarks for JVM, JS, WasmJS, Native
- `src/androidInstrumentedTest/kotlin/` - AndroidX Benchmark tests

## Performance Optimization Guidelines

When optimizing buffer operations, follow these principles:

### Apple/Native Platform
1. **Avoid object allocation in hot paths** - Kotlin/Native GC can't keep up with millions of allocations/sec
2. **Use pointer arithmetic** - `CPointer + offset` compiles to single CPU instruction, zero allocation
3. **Use `reinterpret<>()` for multi-byte reads** - Avoids byte-by-byte assembly
4. **Prefer `memcpy` over intermediate arrays** - Direct memory-to-memory copy
5. **Avoid `subdataWithRange()`** - Creates new NSData objects, causes GC pressure

### JVM/Android
1. **Direct ByteBuffers** are best for I/O (avoid extra copy)
2. **Heap allocation is faster** (7.6M vs 1.3M ops/s) but Direct is better for I/O
3. **Bulk operations are extremely fast** (46-56M ops/s)

### JavaScript
1. **Batch operations** - Primitive read/write is slow (36K ops/s)
2. **Use bulk operations** when possible (10M ops/s)
3. Heap and Direct are equivalent (both use Uint8Array)

### WasmJS
1. **LinearBuffer (Direct)** - Uses native WASM Pointer ops, 25% faster than ByteArrayBuffer
2. **ByteArrayBuffer (Heap)** - Use for high-frequency allocations (no memory limit)
3. **Bulk operations are 2x faster** than single operations on LinearBuffer
4. **256MB pre-allocated** - LinearBuffer uses bump allocator due to optimizer bug workaround
5. **Use Direct for JS interop** - Zero-copy sharing with JavaScript via `wasmMemory.buffer`

See `PERFORMANCE.md` for detailed benchmark results.

## CI/CD

- Builds run on macOS with JDK 21
- PR labels control version bumping: `major`, `minor`, or patch (default)
- Publishing to Maven Central happens automatically on PR merge to main
