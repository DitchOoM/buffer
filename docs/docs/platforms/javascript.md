---
sidebar_position: 4
title: JavaScript
---

# JavaScript Platform

Buffer on JS wraps `Uint8Array` with optional `SharedArrayBuffer` support.

## Implementation

| Zone | JS Type |
|------|---------|
| `Heap` | `Uint8Array` (ArrayBuffer) |
| `Direct` | `Uint8Array` (ArrayBuffer) |
| `SharedMemory` | `Uint8Array` (SharedArrayBuffer) |

## SharedArrayBuffer

For multi-threaded scenarios (Web Workers):

```kotlin
val buffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)

// Access the SharedArrayBuffer
val jsBuffer = buffer as JsBuffer
// jsBuffer.sharedArrayBuffer  // Only set if SharedMemory zone
```

### CORS Requirements

SharedArrayBuffer requires specific HTTP headers:

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
```

### Webpack Configuration

Add to `webpack.config.d/headers.js`:

```javascript
if (config.devServer != null) {
    config.devServer.headers = {
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp"
    }
}
```

Without these headers, falls back to regular `ArrayBuffer`.

## Node.js vs Browser

### Node.js

```kotlin
// Works with Node.js Buffer
val buffer = PlatformBuffer.allocate(1024)
buffer.writeString("Hello from Kotlin/JS!")
```

### Browser

```kotlin
// Works with browser APIs
val buffer = PlatformBuffer.allocate(1024)

// Can be used with Fetch API, WebSocket, etc.
buffer.writeBytes(responseData)
```

## Web Worker Communication

```kotlin
// Main thread
val sharedBuffer = PlatformBuffer.allocate(1024, AllocationZone.SharedMemory)
sharedBuffer.writeInt(42)

// Pass to worker (zero-copy with SharedArrayBuffer)
worker.postMessage(sharedBuffer.asArrayBuffer())

// Worker thread
val buffer = PlatformBuffer.wrap(receivedArrayBuffer)
val value = buffer.readInt()  // 42
```

## Native Data Conversion

Convert buffers to JavaScript-native types for Web API interop:

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeBytes(data)
buffer.resetForRead()

// Get ArrayBuffer (for Fetch, WebSocket, etc.)
val nativeData = buffer.toNativeData()
val arrayBuffer: ArrayBuffer = nativeData.arrayBuffer
fetch(url, RequestInit(body = arrayBuffer))

// Get Int8Array (mutable view)
val mutableData = buffer.toMutableNativeData()
val int8Array: Int8Array = mutableData.int8Array
```

### Zero-Copy Behavior

| Conversion | JsBuffer |
|------------|----------|
| `toNativeData()` | Zero-copy for mutable buffers, copy for read-only |
| `toMutableNativeData()` | Zero-copy (subarray view) |
| `toByteArray()` | Zero-copy (subarray) |

:::note ArrayBuffer Limitation
`ArrayBuffer` itself is immutable (you can't create a view of a portion). When converting to `ArrayBuffer`, a copy may be needed for partial views. `Int8Array` supports zero-copy subarray views.
:::

### Web API Examples

```kotlin
// Fetch API
val response = fetch(url).await()
val arrayBuffer = response.arrayBuffer().await()
val buffer = PlatformBuffer.wrap(arrayBuffer)

// WebSocket
webSocket.onmessage = { event ->
    val data = event.data as ArrayBuffer
    val buffer = PlatformBuffer.wrap(data)
    processMessage(buffer)
}

// Send via WebSocket
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(messageId)
buffer.writeString(payload)
buffer.resetForRead()
webSocket.send(buffer.toNativeData().arrayBuffer)
```

See [Platform Interop](../recipes/platform-interop) for more details.

## Best Practices

1. **Configure CORS headers** for SharedArrayBuffer
2. **Use SharedMemory** for Worker communication
3. **Pool buffers** for WebSocket message handling
4. **Handle fallback** when SharedArrayBuffer unavailable
