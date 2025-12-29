---
sidebar_position: 5
title: WASM
---

# WebAssembly Platform

Buffer on Kotlin/WASM currently uses `ByteArray` with optimization opportunities.

## Current Implementation

| Zone | WASM Type |
|------|-----------|
| All zones | `ByteArray` |

## Limitations

The current WASM implementation:

- Uses Kotlin `ByteArray` internally
- May involve copies when interfacing with JavaScript
- Does not yet use WASM linear memory optimizations

## Usage

```kotlin
val buffer = PlatformBuffer.allocate(1024)
buffer.writeInt(42)
buffer.resetForRead()
val value = buffer.readInt()
```

The API is identical to other platforms.

## Future Optimizations

Potential improvements (contributions welcome):

1. **Direct WASM memory access** - avoid ByteArray copies
2. **WASM SIMD** - vectorized operations
3. **memory.copy/memory.fill** - native bulk operations

## Contributing

If you're interested in optimizing WASM performance:

1. Check the [GitHub issues](https://github.com/DitchOoM/buffer/issues)
2. See `src/wasmJsMain/` for current implementation
3. Benchmark before and after changes

## Best Practices

1. **Pool buffers** - allocation overhead is significant
2. **Batch operations** - reduce JS interop calls
3. **Use bulk writes** - `writeBytes()` over byte-by-byte
