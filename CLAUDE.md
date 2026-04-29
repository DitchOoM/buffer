# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ByteBuffer is a Kotlin Multiplatform library providing platform-agnostic byte buffer management with an API similar to Java's ByteBuffer. It delegates to native implementations on each platform to avoid memory copies.

**Package:** `com.ditchoom.buffer`

## Build Commands

```bash
./gradlew build                   # Build all platforms
./gradlew allTests                # Run tests for all platforms
./gradlew check                   # All checks (tests + linting)
./gradlew ktlintCheck             # Check code style
./gradlew ktlintFormat            # Auto-format code

# Run specific test class
./gradlew :jvmTest --tests "com.ditchoom.buffer.BufferTests"
./gradlew :jsNodeTest --tests "com.ditchoom.buffer.BufferTests"

# Codec tests
./gradlew :buffer-codec-processor:test   # KSP compile-time validation
./gradlew :buffer-codec-test:jvmTest     # Integration tests (TLS, PNG, RIFF, WebSocket, MQTT)
```

## Architecture

### Kotlin Multiplatform Structure

The project uses the expect/actual pattern:

```
src/
├── commonMain/          # Shared interfaces (PlatformBuffer, ReadBuffer, WriteBuffer)
├── jvmCommonMain/       # Shared JVM/Android
├── jvmMain/             # JVM: HeapJvmBuffer, DirectJvmBuffer
├── androidMain/         # Android: extends JVM + SharedMemory/Parcelable IPC
├── appleMain/           # iOS/macOS/watchOS/tvOS: MutableDataBuffer (NSMutableData)
├── jsMain/              # Browser/Node.js: JsBuffer (Int8Array)
├── wasmJsMain/          # WASM: LinearBuffer (native memory) + ByteArrayBuffer (heap)
└── nativeMain/          # Linux/Apple native
```

### Key Interfaces

- `ReadBuffer` / `WriteBuffer` — Read and write operations
- `PlatformBuffer` — Main buffer interface combining both
- `BufferFactory` — Memory allocation: `Default` (native), `managed()` (heap), `shared()` (IPC), `deterministic()` (explicit cleanup)
- `NativeMemoryAccess` / `ManagedMemoryAccess` / `SharedMemoryAccess` — Memory type markers

### Modules

| Module | Purpose |
|--------|---------|
| `buffer` | Core buffer interfaces, pool, stream processor, scoped buffers |
| `buffer-codec` | Codec interfaces (`Encoder`, `Decoder`, `Codec`), annotations, context |
| `buffer-codec-processor` | KSP code generator for `@ProtocolMessage` |
| `buffer-codec-test` | Protocol round-trip integration tests |
| `buffer-compression` | Streaming compression (zlib, gzip, raw deflate) |
| `buffer-flow` | Typed stream abstractions (`Connection`, `Sender`, `Receiver`, `StreamMux`) |

## Rules — Read These First

### Never Use ByteArray

ByteArray guarantees a memory copy. This library exists to avoid that.

- **Never accept `ByteArray` in function signatures** — use `ReadBuffer` / `WriteBuffer`
- **Never return `ByteArray`** — return `ReadBuffer` or a buffer slice
- **Never suggest `ByteArray` as an intermediary** — use `slice()`, `readBytes()`, or `writeString()` directly
- **If you must interop with a ByteArray API**, use `BufferFactory.Default.wrap(byteArray)` to wrap it zero-copy, don't copy into a new buffer
- **In tests**, use typed values (String, Int, Long) and buffer interfaces, not ByteArray

```kotlin
// WRONG
val bytes = buffer.readByteArray(length)
processBytes(bytes)

// CORRECT
val slice = buffer.readBytes(length)  // zero-copy slice
processBuffer(slice)
```

### Always Use `BufferFactory`, Not `PlatformBuffer`

```kotlin
// CORRECT
BufferFactory.Default.allocate(size)
BufferFactory.Default.wrap(byteArray)    // zero-copy wrap if you receive a ByteArray
BufferFactory.managed().allocate(size)   // heap
BufferFactory.shared().allocate(size)    // Android IPC

// WRONG — bypasses factory composition (pooling, monitoring, cleanup)
PlatformBuffer.allocate(size)
PlatformBuffer.wrap(byteArray)
```

Library code should accept `BufferFactory` as a parameter so callers control allocation.

### Wrapper Transparency

Buffer wrappers (PooledBuffer, TrackedSlice) delegate to an underlying PlatformBuffer. Code must work through wrappers:

```kotlin
// CORRECT
val nma = buffer.nativeMemoryAccess
val actual = buffer.unwrapFully()

// WRONG — breaks on PooledBuffer/TrackedSlice
(buffer as? PlatformBuffer)?.unwrap() ?: buffer
```

### Avoid Unnecessary Memory Copies

- Use `slice()` / `readBytes()` instead of `readByteArray()` + `wrap()`
- Use `toNativeData()` / `toMutableNativeData()` for platform interop
- Use `writeString()` directly instead of `encodeToByteArray()` + `writeBytes()`
- Use `BufferPool` in hot paths to reuse buffers
- Accept `ReadBuffer`/`WriteBuffer` in function signatures, not `ByteArray`

## Codec System (`buffer-codec`)

### Directional Codecs

Three interface levels:

- `Encoder<in T>` — encode only. Has `encode(buffer, value, context)` and `wireSize(value, context)`.
- `Decoder<out T>` — decode only. Has `decode(buffer, context): T`. `fun interface` for SAM lambdas.
- `Codec<T>` — bidirectional. Extends both + `FrameDetector`.

### Codec contract — payload bytes only

`encode` writes payload bytes only. `wireSize` returns that same payload-byte count. `decode` reads from the buffer's current position to its natural delimiter (fixed width, terminator) or to `buffer.remaining()` for variable-length payloads — implementations trust the caller's buffer-bounding.

**Framing is the framework's job.** Length prefixes, length-from-other-fields, dispatch headers, and remaining-bytes slicing are all expressed via field annotations on the *consumer*: `@LengthPrefixed`, `@LengthFrom`, `@RemainingBytes`, `@DispatchOn`. Custom `Codec<T>` implementations never write a length prefix themselves; the consumer adds one with `@LengthPrefixed @UseCodec(MyCodec)`.

`encodeToBuffer()` allocates an exact-size buffer using `wireSize`. Hand-written encoders that don't override `wireSize` fall back to a 2x-doubling `GrowableWriteBuffer`. Generated codecs always override.

The two structural assertions baked into `testRoundTrip` — `wireSize == bytes-written` and `decode consumes exactly the payload` — catch regressions where a codec re-introduces internal length-prefix framing.

### Use `@ProtocolMessage` for Structured Data

```kotlin
@ProtocolMessage
data class MyMessage(
    val id: Int,
    val type: Short,
    @LengthPrefixed val payload: String,
)
// Generated: MyMessageCodec.encode(buffer, msg) / MyMessageCodec.decode(buffer)
```

Use `@ProtocolMessage(direction = Direction.DecodeOnly)` or `EncodeOnly` for unidirectional codecs. `@UseCodec` accepts `Decoder<T>`, `Encoder<T>`, or `Codec<T>` — direction validated at compile time.

### Sealed Dispatch

```kotlin
@ProtocolMessage
sealed interface Command {
    @ProtocolMessage @PacketType(0x01)
    data class Ping(val timestamp: Long) : Command
    @ProtocolMessage @PacketType(0x02)
    data class Echo(@LengthPrefixed val message: String) : Command
}
```

For multi-byte or bit-packed discriminators, use `@DispatchOn` + `@DispatchValue`. `wire` values validated at compile time. Duplicate `@PacketType` values are compile errors.

### `@WhenRemaining` — Optional Trailing Fields

```kotlin
@ProtocolMessage
data class AckV5(
    val packetId: UShort,
    @WhenRemaining(1) val reasonCode: UByte? = null,
    @WhenRemaining(1) val properties: Collection<Property>? = null,
)
```

Fields must be nullable with default `null`, contiguous, at constructor tail. Cascading null checks on encode prevent impossible wire states.

### `peekFrameSize` — Stream Framing

Generated when frame size is determinable from wire format:

```kotlin
while (stream.available() >= PacketCodec.MIN_HEADER_BYTES) {
    val frameSize = PacketCodec.peekFrameSize(stream) ?: break
    if (stream.available() < frameSize) break
    val msg = stream.readBufferScoped(frameSize) { PacketCodec.decode(this) }
}
```

### CodecContext

Pass typed configuration through codec chains without global state:

```kotlin
val ctx = DecodeContext.Empty.with(MyCodec.AllocatorKey, hardwareAllocator)
val result = TopLevelCodec.decode(buffer, ctx)
// context flows automatically through sealed dispatch → @UseCodec → nested codecs
```

## Stream Processing (`buffer.stream`)

- `StreamProcessor` — Handles fragmented network data with peek/read. NOT thread-safe.
- `FrameDetector` — Peeks at stream to determine frame boundaries without consuming data.
- Adaptive coalescing: small chunks (< 256 bytes) auto-coalesced when ≥ 8 accumulate.

```kotlin
val processor = StreamProcessor.create(pool)
processor.append(networkData)
val message = processor.readBufferScoped(length) { decode(this) }
```

## Flow Abstractions (`buffer-flow`)

- `Sender<T>` / `Receiver<T>` — Unidirectional typed streams
- `Connection<T>` — Bidirectional (Sender + Receiver + `close()`). NOT thread-safe.
- `StreamMux<T>` — Multiplexed streams (QUIC, HTTP/2). NOT thread-safe.
- `map()` / `contramap()` / `mapNotNull()` — Type-safe protocol layering

## Platform Notes

- **JVM/Android:** Direct ByteBuffers by default; `HeapJvmBuffer` for `wrap()` and `managed()`
- **Android SharedMemory:** `BufferFactory.shared()` for zero-copy IPC (API 27+)
- **Apple:** `MutableDataBuffer` wraps NSMutableData; use `wrap(nsData)` for zero-copy interop
- **JS:** `SharedArrayBuffer` requires CORS headers
- **WASM:** `LinearBuffer` for JS interop; `ByteArrayBuffer` for compute

## CI/CD

- Builds on macOS with JDK 21
- PR labels control version bumping: `major`, `minor`, or patch (default)
- Publishing to Maven Central on merge to main
