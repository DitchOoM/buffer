---
sidebar_position: 6
title: Platform Interop
---

# Platform Interop

Convert buffers to and from platform-native types for seamless FFI, IPC, and API integration.

## Overview

Buffer provides conversion functions that allow you to work with platform-native data types:

| Function | Description |
|----------|-------------|
| `toByteArray()` | Convert remaining bytes to Kotlin `ByteArray` |
| `toNativeData()` | Convert to read-only native wrapper (`NativeData`) |
| `toMutableNativeData()` | Convert to mutable native wrapper (`MutableNativeData`) |

All three functions operate on the **remaining** bytes (from `position()` to `limit()`) and **do not modify** the buffer's position or limit.

## toByteArray()

Converts the remaining bytes to a Kotlin `ByteArray`:

```kotlin
val buffer = PlatformBuffer.allocate(100)
buffer.writeInt(42)
buffer.writeString("Hello")
buffer.resetForRead()

// Convert remaining bytes to ByteArray
val bytes = buffer.toByteArray()

// Position is unchanged - can read again
val value = buffer.readInt()  // 42
```

### Zero-Copy Behavior

| Platform | Backing Type | Zero-Copy? |
|----------|--------------|------------|
| JVM Heap | `HeapByteBuffer` | Yes (returns backing array when possible) |
| JVM Direct | `DirectByteBuffer` | No (must copy from off-heap) |
| Apple | `MutableDataBuffer` | No (must copy from NSData) |
| JS | `JsBuffer` | Yes (subarray view) |
| WASM | `LinearBuffer` | No (different memory spaces) |
| Linux | `NativeBuffer` | No (must copy from native memory) |

:::tip When to Use
Use `toByteArray()` when you need a Kotlin `ByteArray` for:
- Serialization libraries that require `ByteArray`
- Hash functions (e.g., `MessageDigest.update(bytes)`)
- Base64 encoding
- Interop with pure-Kotlin libraries
:::

## toNativeData()

Converts to a platform-native read-only wrapper. Each platform returns a `NativeData` object containing the native type:

```kotlin
val buffer = PlatformBuffer.allocate(100)
buffer.writeBytes(data)
buffer.resetForRead()

// Convert to platform-native wrapper
val native = buffer.toNativeData()
```

### Platform-Specific Access

```kotlin
// JVM/Android
val byteBuffer: ByteBuffer = native.byteBuffer

// Apple (iOS/macOS)
val nsData: NSData = native.nsData

// JavaScript
val arrayBuffer: ArrayBuffer = native.arrayBuffer

// WASM
val linearBuffer: LinearBuffer = native.linearBuffer

// Linux
val nativeBuffer: NativeBuffer = native.nativeBuffer
```

### Zero-Copy Behavior

| Platform | Native Type | Zero-Copy? |
|----------|-------------|------------|
| JVM/Android | `ByteBuffer` (direct) | Yes for Direct buffers, copies Heap to Direct |
| Apple | `NSData` | Yes (subdataWithRange) |
| JS | `ArrayBuffer` | Partial (zero-copy for mutable buffers) |
| WASM | `LinearBuffer` | Yes (slice view) |
| Linux | `NativeBuffer` | No (NativeBuffer owns memory) |

:::note JVM Always Returns Direct
On JVM, `toNativeData()` and `toMutableNativeData()` always return direct ByteBuffers for native memory interop. If the source is a heap buffer, the data is copied to a new direct buffer.
:::

## toMutableNativeData()

Converts to a platform-native mutable wrapper. Returns a `MutableNativeData` object:

```kotlin
val buffer = PlatformBuffer.allocate(100)
buffer.writeBytes(data)
buffer.resetForRead()

// Convert to mutable native wrapper
val mutableNative = buffer.toMutableNativeData()
```

### Platform-Specific Access

```kotlin
// JVM/Android
val byteBuffer: ByteBuffer = mutableNative.byteBuffer

// Apple (iOS/macOS)
val nsMutableData: NSMutableData = mutableNative.nsMutableData

// JavaScript
val int8Array: Int8Array = mutableNative.int8Array

// WASM
val linearBuffer: LinearBuffer = mutableNative.linearBuffer

// Linux
val nativeBuffer: NativeBuffer = mutableNative.nativeBuffer
```

### Zero-Copy Behavior

| Platform | Mutable Type | Zero-Copy? |
|----------|--------------|------------|
| JVM/Android | `ByteBuffer` (direct) | Yes for Direct buffers, copies Heap to Direct |
| Apple | `NSMutableData` | Partial (full buffer only) |
| JS | `Int8Array` | Yes (subarray view) |
| WASM | `LinearBuffer` | Yes (shared memory) |
| Linux | `NativeBuffer` | No (always copies) |

:::note Apple Platform
On Apple, `toMutableNativeData()` is zero-copy only when the buffer position is 0 and the entire buffer is used. Partial views require a copy because `NSMutableData` needs mutable ownership of its memory.
:::

## Position Invariance

All conversion functions preserve the buffer's position and limit:

```kotlin
val buffer = PlatformBuffer.allocate(100)
buffer.writeInt(1)
buffer.writeInt(2)
buffer.writeInt(3)
buffer.resetForRead()

// Read first int
buffer.readInt()  // position is now 4

val positionBefore = buffer.position()  // 4
val limitBefore = buffer.limit()        // 12

// Convert remaining bytes
val bytes = buffer.toByteArray()        // contains bytes for ints 2 and 3

// Position and limit unchanged
assertEquals(positionBefore, buffer.position())  // still 4
assertEquals(limitBefore, buffer.limit())        // still 12

// Can continue reading
val second = buffer.readInt()  // 2
```

## Use Cases

### FFI / JNI Interop

```kotlin
// JVM: Pass to native code via JNI
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
buffer.writeBytes(data)
buffer.resetForRead()

val nativeData = buffer.toNativeData()
nativeLibrary.processData(nativeData.byteBuffer)
```

### Apple Framework Integration

```kotlin
// iOS/macOS: Pass to Foundation/CoreFoundation APIs
val buffer = PlatformBuffer.allocate(1024)
buffer.writeBytes(imageData)
buffer.resetForRead()

val native = buffer.toNativeData()
NSFileManager.defaultManager.createFileAtPath(path, native.nsData, null)
```

### JavaScript Web APIs

```kotlin
// JS: Use with Fetch, WebSocket, etc.
val buffer = PlatformBuffer.allocate(1024)
buffer.writeBytes(payload)
buffer.resetForRead()

val native = buffer.toNativeData()
fetch(url, RequestInit(body = native.arrayBuffer))
```

### WASM JavaScript Interop

```kotlin
// WASM: Share memory with JavaScript
val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct) as LinearBuffer
buffer.writeBytes(data)
buffer.resetForRead()

// JavaScript can read the same memory via wasmMemory.buffer
val offset = buffer.linearMemoryOffset
```

### Linux io_uring

```kotlin
// Linux: Zero-copy I/O with io_uring
val buffer = NativeBuffer.allocate(65536)
// ... write data ...
buffer.resetForRead()

val native = buffer.toNativeData()
val ptr = native.nativeBuffer.nativeAddress.toCPointer<ByteVar>()
io_uring_prep_send(sqe, sockfd, ptr, buffer.remaining().toULong(), 0)
```

## Wrapping Native Data

You can also create buffers from native platform types:

```kotlin
// JVM: Wrap ByteBuffer
val nioBuffer = ByteBuffer.allocate(1024)
val buffer = PlatformBuffer.wrap(nioBuffer)

// Apple: Wrap NSData
val nsData: NSData = // ... from API ...
val buffer = PlatformBuffer.wrap(nsData)

// Apple: Wrap NSMutableData (zero-copy mutable)
val nsMutableData: NSMutableData = // ... from API ...
val buffer = PlatformBuffer.wrap(nsMutableData)

// JS: Wrap ArrayBuffer
val arrayBuffer: ArrayBuffer = // ... from API ...
val buffer = PlatformBuffer.wrap(arrayBuffer)
```

## Best Practices

1. **Prefer native types for I/O** - Use `toNativeData()` instead of `toByteArray()` when passing to platform APIs
2. **Check zero-copy paths** - Understand which conversions copy and which share memory
3. **Use Direct buffers** - Direct allocation often enables zero-copy conversion
4. **Position awareness** - Conversions operate on remaining bytes, not entire buffer
5. **Reuse buffers** - Convert once and reuse the native reference when possible
