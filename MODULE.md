# Module buffer

ByteBuffer is a Kotlin Multiplatform library for platform-agnostic byte buffer management.

## Package com.ditchoom.buffer

Core buffer interfaces and implementations:
- `PlatformBuffer` - Main buffer interface combining read/write operations
- `ReadBuffer` - Read operations (relative and absolute)
- `WriteBuffer` - Write operations (relative and absolute)
- `AllocationZone` - Memory allocation strategy

## Package com.ditchoom.buffer.pool

High-performance buffer pooling:
- `BufferPool` - Pool interface with SingleThreaded and MultiThreaded modes
- `PooledBuffer` - Buffer acquired from pool
- `withBuffer` / `withPool` - Recommended usage patterns

## Package com.ditchoom.buffer.stream

Stream processing for chunked data:
- `BufferStream` - Iterate over buffers in chunks
- `StreamProcessor` - Handle fragmented data with peek/read operations
