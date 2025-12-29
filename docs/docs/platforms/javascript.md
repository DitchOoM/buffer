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

## Best Practices

1. **Configure CORS headers** for SharedArrayBuffer
2. **Use SharedMemory** for Worker communication
3. **Pool buffers** for WebSocket message handling
4. **Handle fallback** when SharedArrayBuffer unavailable
