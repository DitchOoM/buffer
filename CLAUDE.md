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
```

## Architecture

### Kotlin Multiplatform Structure

The project uses the expect/actual pattern with platform-specific implementations:

```
src/
├── commonMain/          # Shared interfaces (PlatformBuffer, ReadBuffer, WriteBuffer)
├── commonTest/          # Shared tests run on all platforms
├── jvmMain/             # JVM: wraps java.nio.ByteBuffer
├── androidMain/         # Android: extends JVM + SharedMemory/Parcelable IPC
├── appleMain/           # iOS/macOS/watchOS/tvOS: wraps NSData/NSMutableData
├── jsMain/              # Browser/Node.js: wraps Uint8Array (SharedArrayBuffer support)
├── wasmJsMain/          # WASM: uses Kotlin ByteArray (optimization contributions welcome)
└── nativeMain/          # Linux: uses Kotlin ByteArray
```

### Key Interfaces

- `PlatformBuffer` - Main buffer interface combining read/write operations
- `ReadBuffer` - Read operations (relative and absolute)
- `WriteBuffer` - Write operations (relative and absolute)
- `AllocationZone` - Memory allocation strategy: `Heap`, `Direct`, `SharedMemory`, `Custom`

### Factory Pattern

Buffers are created via companion object methods:
```kotlin
PlatformBuffer.allocate(size, zone = AllocationZone.Direct, byteOrder = ByteOrder.BIG_ENDIAN)
PlatformBuffer.wrap(byteArray)
```

## Platform Notes

- **JVM/Android:** Direct ByteBuffers used by default to avoid copies
- **Android SharedMemory:** Use `AllocationZone.SharedMemory` for zero-copy IPC via Parcelable
- **JS SharedArrayBuffer:** Requires CORS headers (`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`)
- **WASM:** Currently uses ByteArray copies - optimization contributions welcome

## CI/CD

- Builds run on macOS with JDK 19
- PR labels control version bumping: `major`, `minor`, or patch (default)
- Publishing to Maven Central happens automatically on PR merge to main
