[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]


<!-- PROJECT LOGO -->
<!--suppress ALL -->

<br />
<p align="center">
<h3 align="center">ByteBuffer</h3>

<p align="center">
A kotlin multiplatform library that allows you to allocate and modify byte[] natively using an API similar to <a href="https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html"><strong>Java's ByteBuffer API.</strong></a>
<br />
<!-- <a href="https://github.com/DitchOoM/buffer"><strong>Explore the docs »</strong></a> -->
<br />
<br />
<!-- <a href="https://github.com/DitchOoM/buffer">View Demo</a>
· -->
<a href="https://github.com/DitchOoM/buffer/issues">Report Bug</a>
·
<a href="https://github.com/DitchOoM/buffer/issues">Request Feature</a>
</p>


<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#runtime-dependencies">Runtime Dependencies</a></li>
      </ul>
      <ul>
        <li><a href="#supported-platforms">Supported Platforms</a></li>
      </ul>
    </li>
    <li><a href="#installation">Installation</a></li>
    <li>
      <a href="#usage">Usage</a>
      <ul>
        <li><a href="#allocate-a-new-platform-agnostic-buffer">Allocate a new platform agnostic buffer</a></li>
        <li><a href="#allocation-zones">Allocation Zones</a></li>
        <li><a href="#byte-order">Byte order</a></li>
        <li><a href="#relative-write-data-into-platform-agnostic-buffer">Relative write data into platform agnostic buffer</a></li>
        <li><a href="#absolute-write-data-into-platform-agnostic-buffer">Absolute write data into platform agnostic buffer</a></li>
        <li><a href="#relative-read-data-into-platform-agnostic-buffer">Relative read data into platform agnostic buffer</a></li>
        <li><a href="#absolute-read-data-into-platform-agnostic-buffer">Absolute read data into platform agnostic buffer</a></li>
        <li><a href="#buffer-pool">Buffer Pool</a></li>
        <li><a href="#buffer-stream">Buffer Stream</a></li>
      </ul>
    </li>
    <li>
      <a href="#building-locally">Building Locally</a>
    </li>
    <li><a href="#getting-started">Getting Started</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
  </ol>
</details>

## About The Project

Allocating and managing a chunk of memory can be slightly different based on each platform. This
project aims to make it **easier to manage buffers in a cross platform way using kotlin
multiplatform**. This was originally created as a side project for a kotlin multiplatform mqtt data
sync solution.


This library allows Android/JVM users to access ByteBuffers without incurring extra memory copies 
compared to okio which uses `byte[]` internally which forces Android/JVM users to copy data which 
can cause significant performance issues in some cases.

Implementation notes:

* `JVM` + `Android` delegate to direct [ByteBuffers][byte-buffer-api] to avoid memory copies when
  possible.
* Apple targets use NSData or NSMutableData
* `JS` targets use Uint8Array.
* `Native` platforms use standard byte arrays to manage memory.

### Runtime Dependencies

* None

### [Supported Platforms](https://kotlinlang.org/docs/reference/mpp-supported-platforms.html)

* All Kotlin Multiplatform supported OS's.

|      Platform      |                                                                            Wrapped Type                                                                             |  
|:------------------:|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
|     `JVM` 1.8      |                                 [ByteBuffer](https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/nio/ByteBuffer.html)                                 |
|     `Node.js`      |               [Uint8Array](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Uint8Array)  including SharedArrayBuffer                |
| `Browser` (Chrome) |                [Uint8Array](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Uint8Array) including SharedArrayBuffer                |
|  `WASM - Node.js`  |                                                   Kotlin ByteArray -- Looking for contributions to remove copies                                                    |
| `WASM - Browser` (Chrome) |                Kotlin ByteArray -- Looking for contributions to remove copies                |
|     `Android`      | [ByteBuffer](https://developer.android.com/reference/java/nio/ByteBuffer) including [SharedMemory](https://developer.android.com/reference/android/os/SharedMemory) |
|       `iOS`        |                                         [NSData](https://developer.apple.com/documentation/foundation/nsdata?language=objc)                                         |
|     `WatchOS`      |                                         [NSData](https://developer.apple.com/documentation/foundation/nsdata?language=objc)                                         |
|       `TvOS`       |                                         [NSData](https://developer.apple.com/documentation/foundation/nsdata?language=objc)                                         |
|      `MacOS`       |                                         [NSData](https://developer.apple.com/documentation/foundation/nsdata?language=objc)                                         |
|    `Linux X64`     |                                                                          kotlin ByteArray                                                                           |
|   `Windows X64`    |                                                                                TODO                                                                                 |

## Installation

### Gradle

- [Add `implementation("com.ditchoom:buffer:$version")` to your `build.gradle` dependencies](https://search.maven.org/artifact/com.ditchoom/buffer)

## Usage

### Allocate a new platform agnostic buffer

```kotlin
val buffer = PlatformBuffer.allocate(
    byteSize,
    zone = AllocationZone.Direct,
    byteOrder = ByteOrder.BIG_ENDIAN
)
```

### Wrap an existing byte array into a platform agnostic buffer

```kotlin
val byteArray = byteArrayOf(1, 2, 3, 4, 5)
val buffer = PlatformBuffer.wrap(byteArray, byteOrder = ByteOrder.BIG_ENDIAN)
```

### Allocation Zones

Allocation zones allow you to change where the buffer is allocated.

- `AllocationZone.Custom` -> Allows you to override the underlying buffer. This can be helpful for
  memory mapped structures.
- `AllocationZone.Heap` -> On JVM platforms, allocates a HeapByteBuffer, otherwise a native byte
  array
- `AllocationZone.Direct` -> On JVM platforms, allocates a DirectByteBuffer, otherwise a native byte
  array
- `AllocationZone.SharedMemory` -> On JS Platforms this will populate the `sharedArrayBuffer` parameter in `JsBuffer`.
  On API 27+ it allocates
  a [Shared Memory](https://developer.android.com/reference/android/os/SharedMemory) instance,
  otherwise will pipe the data during parcel using ParcelFileDescriptor and java.nio.Channel api. For `JS` platforms it
  will allocate
  a [`SharedArrayBuffer`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer).
  If the
  proper [security requirements](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/SharedArrayBuffer#security_requirements)
  are not set, it will fallback to a standard `ArrayBuffer`. 

> **Android**: All `JvmBuffer`s are `Parcelable`. To avoid extra memory copies when using IPC, always choose `AllocationZone.SharedMemory`.

> **Browser JS**: To enable SharedArrayBuffer, add the appropriate headers for the JS server in a gradle project by adding any file to a directory `webpack.config.d` next to the `src` directory containing:
>```
>if (config.devServer != null) {
>    config.devServer.headers = {
>        "Cross-Origin-Opener-Policy": "same-origin",
>        "Cross-Origin-Embedder-Policy": "require-corp"
>    }
>}
>```

### Byte order

Byte order defaults to big endian but can be specified when creating the buffer
with `ByteOrder.BIG_ENDIAN`
or `ByteOrder.LITTLE_ENDIAN`

The byte order of a buffer can be checked with `buffer.byteOrder`

### Relative write data into platform agnostic buffer

```kotlin
val buffer: WriteBuffer
// write signed byte
buffer.writeByte(5.toByte())
// write unsigned byte
buffer.writeUByte(5.toUByte())
// write short
buffer.writeShort(5.toShort())
// write unsigned short
buffer.writeUShort(5.toUShort())
// write int
buffer.writeInt(5)
// write unsigned int
buffer.writeUInt(5.toUInt())
// write long
buffer.writeLong(5L)
// write unsigned long
buffer.writeULong(5uL)
// write float
buffer.writeFloat(123.456f)
// write double
buffer.writeDouble(123.456)
// write text
buffer.writeString("5", Charset.UTF8)
// copy buffer into this one
buffer.write(otherBuffer)
// write byte array
buffer.writeBytes(byteArrayOf(1, 2, 3, 4))
// write partial byte array
buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5), offset, length)
```

### Absolute write data into platform agnostic buffer

```kotlin
val buffer: WriteBuffer
// set signed byte
buffer[index] = 5.toByte()
// set unsigned byte
buffer[index] = 5.toUByte()
// set short
buffer[index] = 5.toByte()
// set unsigned short
buffer[index] = 5.toUShort()
// set int
buffer[index] = 5
// set unsigned int
buffer[index] = 5.toUInt()
// set long
buffer[index] = 5L
// set unsigned long
buffer[index] = 5uL
// set float
buffer[index] = 123.456f
// set double
buffer[index] = 123.456
```

### Relative read data into platform agnostic buffer

```kotlin
val buffer: ReadBuffer
// read signed byte
val b = buffer.readByte()
// read unsigned byte
val uByte = buffer.readUnsignedByte()
// read short
val short = buffer.readShort()
// read unsigned short
val uShort = buffer.readUnsignedShort()
// read int
val intValue = buffer.readInt()
// read unsigned int
val uIntValue = buffer.readUnsignedInt()
// read long
val longValue = buffer.readLong()
// read unsigned long
val uLongValue = buffer.readUnsignedLong()
// read float
val float = buffer.readFloat()
// read double
val double = buffer.readDouble()
// read text
val string = buffer.readUtf8(numOfBytesToRead)
// read byte array
val byteArray = buffer.readByteArray(numOfBytesToRead)
// read a shared subsequence read buffer (changes to the original reflect here)
val readBuffer = buffer.readBytes(numOfBytesForBuffer)
```

### Absolute read data into platform agnostic buffer

```kotlin
val buffer: ReadBuffer
// get signed byte
val b = buffer.get(index) // or buffer[index]
// get unsigned byte
val uByte = buffer.getUnsignedByte(index)
// get short
val short = buffer.getShort(index)
// get unsigned short
val uShort = buffer.getUnsignedShort(index)
// get int
val intValue = buffer.getInt(index)
// get unsigned int
val uIntValue = buffer.getUnsignedInt(index)
// get long
val longValue = buffer.getLong(index)
// get unsigned long
val uLongValue = buffer.getUnsignedLong(index)
// get float
val float = buffer.getFloat(index)
// get double
val double = buffer.getDouble(index)
// slice the buffer without adjusting the position or limit (changes to the original reflect here)
val slicedBuffer = buffer.slice()
```

### Buffer Pool

High-performance buffer pooling to minimize allocations in hot paths like network I/O:

```kotlin
import com.ditchoom.buffer.pool.*

// Create a pool (single-threaded for best performance)
val pool = BufferPool(
    threadingMode = ThreadingMode.SingleThreaded,  // or MultiThreaded for concurrent access
    maxPoolSize = 64,
    defaultBufferSize = 8 * 1024,  // 8KB default
    byteOrder = ByteOrder.BIG_ENDIAN
)

// Recommended: use withBuffer for automatic acquire/release
pool.withBuffer(1024) { buffer ->
    buffer.writeInt(42)
    buffer.resetForRead()
    println(buffer.readInt())
} // buffer automatically released

// Or use withPool for scoped pool lifetime
withPool(defaultBufferSize = 8192) { pool ->
    pool.withBuffer { buffer ->
        // use buffer
    }
} // pool automatically cleared

// Manual acquire/release (use when buffer lifetime spans multiple scopes)
val buffer = pool.acquire(1024)
try {
    buffer.writeInt(123)
} finally {
    buffer.release()  // returns buffer to pool
}

// Check pool statistics
val stats = pool.stats()
println("Hits: ${stats.poolHits}, Misses: ${stats.poolMisses}")

// Clean up
pool.clear()
```

**Threading modes:**
- `SingleThreaded` - Fastest, use when pool is confined to a single thread/coroutine
- `MultiThreaded` - Thread-safe lock-free implementation for concurrent access (e.g., acquire on IO thread, release from processing thread)

### Buffer Stream

Process large buffers or streaming data in chunks without loading everything into memory:

```kotlin
import com.ditchoom.buffer.stream.*
import com.ditchoom.buffer.pool.*

// Create a stream from an existing buffer
val sourceBuffer = PlatformBuffer.allocate(1024 * 1024)  // 1MB
// ... populate buffer ...
sourceBuffer.resetForRead()

val stream = BufferStream(sourceBuffer, chunkSize = 8192)

// Process chunks
stream.forEachChunk { chunk ->
    // Process each 8KB chunk
    processChunk(chunk)
}

// Or use StreamProcessor for protocol parsing with fragmented data
val pool = BufferPool(defaultBufferSize = 8192)
val processor = StreamProcessor.create(pool)

// Append incoming data (e.g., from network)
processor.append(incomingBuffer1)
processor.append(incomingBuffer2)

// Parse protocol messages
while (processor.available() >= 4) {
    val messageLength = processor.peekInt()  // peek without consuming
    if (processor.available() >= 4 + messageLength) {
        processor.skip(4)  // skip length header
        val payload = processor.readBuffer(messageLength)
        handleMessage(payload)
    } else {
        break  // wait for more data
    }
}

// Clean up
processor.release()
pool.clear()
```

**StreamProcessor features:**
- Handles data spanning multiple chunks transparently
- `peekByte/peekShort/peekInt/peekLong` - read without consuming
- `peekMatches(pattern)` - check for magic bytes/headers (returns Boolean)
- `peekMismatch(pattern)` - find first mismatch index, or -1 if match (optimized with 8-byte comparisons)
- `readBuffer(size)` - read into a new buffer
- `skip(count)` - skip bytes efficiently
- Automatic chunk cleanup after consumption

## Building Locally

- `git clone git@github.com:DitchOoM/buffer.git`
- Open cloned directory with [Intellij IDEA](https://www.jetbrains.com/idea/download).
    - Be sure
      to [open with gradle](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)

## Roadmap

See the [open issues](https://github.com/DitchOoM/buffer/issues) for a list of proposed features (
and known issues).

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire,
and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

[contributors-shield]: https://img.shields.io/github/contributors/DitchOoM/buffer.svg?style=for-the-badge

[contributors-url]: https://github.com/DitchOoM/buffer/graphs/contributors

[forks-shield]: https://img.shields.io/github/forks/DitchOoM/buffer.svg?style=for-the-badge

[forks-url]: https://github.com/DitchOoM/buffer/network/members

[stars-shield]: https://img.shields.io/github/stars/DitchOoM/buffer.svg?style=for-the-badge

[stars-url]: https://github.com/DitchOoM/buffer/stargazers

[issues-shield]: https://img.shields.io/github/issues/DitchOoM/buffer.svg?style=for-the-badge

[issues-url]: https://github.com/DitchOoM/buffer/issues

[license-shield]: https://img.shields.io/github/license/DitchOoM/buffer.svg?style=for-the-badge

[license-url]: https://github.com/DitchOoM/buffer/blob/master/LICENSE.md

[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555

[linkedin-url]: https://www.linkedin.com/in/thebehera

[byte-buffer-api]: https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html

[maven-central]: https://search.maven.org/search?q=com.ditchoom

[npm]: https://www.npmjs.com/search?q=ditchoom-buffer

[cocoapods]: https://cocoapods.org/pods/DitchOoM-buffer

[apt]: https://packages.ubuntu.com/search?keywords=ditchoom&searchon=names&suite=groovy&section=all

[yum]: https://pkgs.org/search/?q=DitchOoM-buffer

[chocolately]: https://chocolatey.org/packages?q=DitchOoM-buffer
