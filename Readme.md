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
        <li><a href="#allocation-zone">Allocation Zones</a></li>
        <li><a href="#byte-order">Byte order</a></li>
        <li><a href="#write-data-into-platform-agnostic-buffer">Write data into platform agnostic buffer</a></li>
        <li><a href="#read-data-into-platform-agnostic-buffer">Read data into platform agnostic buffer</a></li>
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

Allocating and managing a chunk of memory can be slightly different based on each platform. This project aims to make
it **easier to manage buffers in a cross platform way using kotlin multiplatform**. This was originally created as a
side project for a kotlin multiplatform mqtt data sync solution.

Implementation notes:

* `JVM` + `Android` delegate to direct [ByteBuffers][byte-buffer-api] to avoid memory copies when possible.
* `Native` platforms use standard byte arrays to manage memory.
* `JS` targets use Uint8Array.

### Runtime Dependencies

* None

### [Supported Platforms](https://kotlinlang.org/docs/reference/mpp-supported-platforms.html)

| Platform | 🛠Builds🛠 + 🔬Tests🔬 | Deployed Artifact | Non Kotlin Sample |  
| :---: | :---: | :---: | :---: |
| `JVM` 1.8 |🚀| [maven central][maven-central] 🔮|🔮|
| `Node.js` |🚀|[npm][npm] 🔮|🔮|
| `Browser` (Chrome) |🚀|[npm][npm] 🔮|🔮|
| `Android` |🚀|[maven central][maven-central]  🔮|🔮|
| `iOS` |🚀|[cocoapods][cocoapods] 🔮|🔮|
| `WatchOS` |🚀|[cocoapods][cocoapods] 🔮|🔮|
| `TvOS` |🚀|[cocoapods][cocoapods] 🔮|🔮|
| `MacOS` |🚀|[cocoapods][cocoapods] 🔮|🔮|
| `Linux X64` |🚀|[apt][apt]/[yum][yum] 🔮|🔮|
| `Windows X64` |🚀|[chocolatey][chocolately] 🔮|🔮|

## Installation

- **_TODO_**: Add explanation after artifacts are deployed

## Usage

### Allocate a new platform agnostic buffer

```kotlin
val buffer = PlatformBuffer.allocate(byteSize, zone = AllocationZone.Direct, byteOrder = ByteOrder.BIG_ENDIAN)
```

### Wrap an existing byte array into a platform agnostic buffer

```kotlin
val byteArray = byteArrayOf(1, 2, 3, 4, 5)
val buffer = PlatformBuffer.wrap(byteArray, byteOrder = ByteOrder.BIG_ENDIAN)
```

### Allocation Zones
Allocation zones allow you to change where the buffer is allocated.
- `AllocationZone.Custom` -> Allows you to override the underlying buffer. This can be helpful for memory mapped structures.
- `AllocationZone.Heap` -> On JVM platforms, allocates a HeapByteBuffer, otherwise a native byte array
- `AllocationZone.Direct` -> On JVM platforms, allocates a DirectByteBuffer, otherwise a native byte array
- `AllocationZone.AndroidSharedMemory` -> On API 27+ it allocates a [Shared Memory](https://developer.android.com/reference/android/os/SharedMemory) instance, otherwise defaulting to `AllocationZone.Direct`.

> **Android**: All `JvmBuffer`s are `Parcelable`. To avoid extra memory copies, use `AllocationZone.AndroidSharedMemory`

### Byte order

Byte order defaults to big endian but can be specified when creating the buffer with `ByteOrder.BIG_ENDIAN`
or `ByteOrder.LITTLE_ENDIAN`

The byte order of a buffer can be checked with `buffer.byteOrder`

### Write data into platform agnostic buffer

```kotlin
val buffer: WriteBuffer
// write signed byte
buffer.write(5.toByte())
// write unsigned byte
buffer.write(5.toUByte())
// write unsigned short
buffer.write(5.toUShort())
// write unsigned int
buffer.write(5.toUInt())
// write long
buffer.write(5L)
// write float
buffer.write(123.456f)
// write double
buffer.write(123.456)
// write text
buffer.write("5")
// copy buffer into this one
buffer.write(otherBuffer)
// write byte array
buffer.write(byteArrayOf(1, 2, 3, 4))
// write partial byte array
buffer.write(byteArrayOf(1, 2, 3, 4, 5), offset, length)
```

### Read data into platform agnostic buffer

```kotlin
val buffer: ReadBuffer
// read signed byte
buffer.readByte()
// read unsigned byte
buffer.readUnsignedByte()
// read unsigned short
buffer.readUnsignedShort()
// read unsigned int
buffer.readUnsignedInt()
// read long
buffer.readLong()
// read float
buffer.readFloat()
// read double
buffer.readDouble()
// read text
buffer.readUtf8(numOfBytesToRead)
// read byte array
buffer.readByteArray(numOfBytesToRead)
```

## Building Locally

- `git clone git@github.com:DitchOoM/buffer.git`
- Open cloned directory with [Intellij IDEA](https://www.jetbrains.com/idea/download).
    - Be sure to [open with gradle](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)

## Roadmap

See the [open issues](https://github.com/DitchOoM/buffer/issues) for a list of proposed features (and known issues).

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire, and create. Any
contributions you make are **greatly appreciated**.

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
