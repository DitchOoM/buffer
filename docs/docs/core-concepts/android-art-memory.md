---
sidebar_position: 4
title: Android ART Memory & OOM Recovery
---

# Android ART Memory & OOM Recovery

Buffer pooling (see [Buffer Pooling](../recipes/buffer-pooling.md)) recycles buffers instead of
allocating fresh ones: `pool.acquire()` hands out a cached buffer, `freeNativeMemory()` returns it
to the pool. When the pool misses — no cached buffer of the right size — it falls back to its seed
[`BufferFactory`](./allocation-zones.md). *Which* factory that is decides **where the memory
lives**, and on Android that choice is the difference between "fine under load" and an
`OutOfMemoryError` after sustained churn, even though the factory is producing exactly the
`Default` buffers this library recommends everywhere else.

This page explains why Android is special, how the built-in factories differ across platforms so
you can reason about the tradeoff, and what the pool now does automatically to recover from it. For
the underlying ART mechanics, version matrix, and a local repro recipe, see
[`ANDROID_ART_ALLOCATOR.md`](https://github.com/DitchOoM/buffer/blob/main/ANDROID_ART_ALLOCATOR.md)
in the repo root — this page is the high-level summary, not a replacement.

## How ART Allocates `BufferFactory.Default`

On every other platform, `BufferFactory.Default` allocates memory that lives outside of, or is
transparently reclaimed from, the runtime's tracing GC (native malloc, ARC, an FFM arena). Android
is the exception: `ByteBuffer.allocateDirect(n)` on ART is backed by a **pinned, non-movable
`byte[]`** that lives inside ART's own managed heap and counts against the app's heap budget.

Where that array lands depends on its size:

- **&lt; ~12 KiB** — the non-moving space. dlmalloc-style, fixed capacity (~60 MB in the observed
  CI image), and it is **never compacted**.
- **≥ ~12 KiB** — the Large Object Space (LOS), a freelist over a reserved region. When an LOS
  allocation fails, ART retries it in the non-moving space, so even large-buffer OOMs can end up
  reporting non-moving-space stats.

ART's garbage collector *is* compacting — it can relocate ordinary heap objects to defragment — but
it categorically **cannot move a pinned non-movable array**, because native code may be holding a
raw pointer into it. So under sustained allocate/drop churn of direct buffers, the non-moving space
(and the LOS freelist) fragments in one direction only: the GC can free individual arrays as they
become unreachable, but it can never slide the survivors together to open up a contiguous gap.

This is also why `BufferFactory.managed()` doesn't have the problem: a `HeapByteBuffer` wraps an
*ordinary*, movable `byte[]` on the regular Java heap. Only `allocateDirect`'s backing array is
forced non-movable, because native/JNI code needs a stable address into it.

## Reading an ART Fragmentation OOM

The failure signature looks like this:

```
Failed to allocate a 524307 byte allocation with 25149440 free bytes and 182MB until OOM,
target footprint 437558088, growth limit 603979776; failed due to malloc_space fragmentation
(largest possible contiguous allocation 516800 bytes, space in use 49924680 bytes, capacity = 60678144)
```

`free bytes` is far larger than `largest possible contiguous allocation` — that gap **is** the
fragmentation signature: there's plenty of memory in aggregate, just no single run long enough for
the request. Each throw kills whatever allocation triggered it (e.g. a socket read, so the
connection dies), but the process itself survives.

The real-world trigger that motivated this page: a WebSocket client sizing its receive buffer to
`SO_RCVBUF` (512 KiB) — a large, direct, per-read buffer, repeated for the life of every
connection, is close to the worst case for this failure mode. See
[`ANDROID_ART_ALLOCATOR.md`](https://github.com/DitchOoM/buffer/blob/main/ANDROID_ART_ALLOCATOR.md)
for the full reproduction and the ART-version/image matrix (the behavior varies by ART Mainline
version, not just API level).

## Why the Library Is Safe by Default

The library's own default allocation strategy for pools is `BufferFactory.deterministic()`, not
`BufferFactory.Default`. On Android, `deterministic()` allocates via `Unsafe.allocateMemory` (raw
native malloc) and wraps it in a non-owning `DirectByteBuffer` view — it never calls
`ByteBuffer.allocateDirect`, so it never touches ART's non-moving space or LOS at all. That has been
verified to hold up under a deliberately fragmentation-wrecked heap.

The fragmentation path is only reachable when application code explicitly overrides a pool's factory
to `BufferFactory.Default` (or a decorator built on it) and then churns large, direct buffers on
Android under sustained load.

## Comparing `BufferFactory.Default` Across Platforms

| Platform | What `Default` allocates | How it's reclaimed | ART-style fragmentation risk |
|---|---|---|---|
| JVM 9–20 | `DirectByteBuffer` (native malloc, outside the Java heap) | The JDK's own internal Cleaner (a `PhantomReference` callback) frees the native memory once the buffer object is unreachable and a GC runs | No — the backing store is native malloc, not a heap-resident array |
| JVM 21+ | `FfmAutoBuffer` (`Arena.ofAuto()` FFM memory segment) | GC-managed — the arena is closed once the segment becomes unreachable | No |
| Android | `DirectByteBuffer` (`ByteBuffer.allocateDirect`) | GC-managed, but the backing store is a **pinned, non-movable `byte[]`** in ART's non-moving space or LOS | **Yes** — this is the fragmentation-prone path described above |
| Linux/Native | `ByteArrayBuffer` (plain Kotlin `ByteArray`) | Kotlin/Native's own tracing GC | No — `Default` is GC-managed heap memory here, **not** malloc; use `deterministic()`/`allocateNative()` for malloc-backed buffers |
| Apple/Native | `MutableDataBuffer` (`NSMutableData`) | ARC — freed deterministically once the reference count hits zero | No |
| JavaScript | `JsBuffer` (`Int8Array`) | Engine (V8, etc.) garbage collection | No |
| Wasm | `LinearBuffer` (WASM linear memory) | Bump-allocated; there is no per-buffer free — `freeNativeMemory()` is a no-op and the region is only reclaimed as a whole | No — no ART, no managed-heap compaction to fragment |

## Comparing the Built-in Factories

| Factory | What it allocates | Reclamation | ART-safe? | Typical use case |
|---|---|---|---|---|
| `BufferFactory.Default` | Platform-optimal "fast" memory — see table above | Varies by platform (Cleaner, ARC, engine GC, or ART-managed on Android) | **No on Android** under sustained large/odd-size churn | General I/O everywhere except the Android churn pattern |
| `BufferFactory.deterministic()` | JVM 21+: `Arena.ofShared()`/`ofConfined()`; JVM 9-20 & **Android**: raw native malloc via `Unsafe` (bypasses `allocateDirect` entirely); Apple/Linux/Wasm: same as `Default` there, since those are already deterministic; JS: no deterministic alternative — falls back to GC | Explicit, immediate — `.use { }` or `freeNativeMemory()` | **Yes** — routes around both ART spaces on Android | Hot loops, FFI/JNI interop, and any high-churn Android/JVM path where GC pauses or fragmentation matter |
| `BufferFactory.managed()` | `ByteArray`-backed heap buffer everywhere (`HeapByteBuffer` on JVM/Android, `ByteArrayBuffer` on Apple/Linux/Wasm; same as `Default` on JS) | Ordinary GC | **Yes** — a heap `byte[]` is an ordinary, *movable* object, unlike `allocateDirect`'s pinned array | Short-lived, small buffers where native/off-heap access isn't required |
| `BufferFactory.shared()` | Android: `SharedMemory` (API 27+); falls back to plain `DirectByteBuffer` if creation fails, the size is 0, or the API level is too old. JS: `SharedArrayBuffer` (needs COOP/COEP headers), else falls back to a regular `Int8Array`. All other platforms fall back to `Default` | Reference-counted (`SharedMemory`/ashmem) or whatever the fallback uses | Inherits `Default`'s exposure whenever it falls back to `allocateDirect` on Android | Cross-process IPC (Android Binder, multi-worker JS) |

## The Pool's OOM-Recovery Net

Beyond choosing a safer factory, `BufferPool` (`LockFreeBufferPool` and `SingleThreadedBufferPool`)
now has a built-in recovery path for the miss case, added in
[buffer#266](https://github.com/DitchOoM/buffer/pull/266): when the seed factory's `allocate()`
throws `OutOfMemoryError`, the pool

1. drains its own cached buffers via `clear()` — freeing memory the pool itself was pinning, and
2. on **JVM/Android**, additionally hints `System.gc()` before retrying. ART exposes no direct
   cleaner hook, so simply dropping the reference during `clear()` frees nothing by itself — a GC
   cycle has to actually run for the (now unreachable) `DirectByteBuffer`s to be collected.
3. **retries the allocation exactly once.** A second consecutive `OutOfMemoryError` propagates to
   the caller unchanged.

Kotlin/Native retries the same way but skips the GC hint — `NativeBuffer.freeNativeMemory()` calls
`free()` eagerly, so `clear()` alone already returns the memory to the allocator.

JS and Wasm are a straight passthrough — `allocate()` is called with no catch at all. This isn't a
gap in the recovery path; there's nothing for it to do on those platforms. Both surface no
catchable allocation-failure error to begin with, and neither has a separate cleaner-managed native
store or an ART-style non-movable-array heap to defragment — their buffers are already fully
engine/GC-managed, so "drain and hint a GC" has no counterpart to reclaim.

:::info Scope
This recovery net only helps pools whose seed factory can throw `OutOfMemoryError` in the first
place. It buys resilience for code that has (deliberately or not) configured a pool with
`BufferFactory.Default` on Android; it does not change what `Default` allocates, and it's not a
substitute for `deterministic()` in a hot, high-churn path.
:::

## Related Fixes

- [buffer#266](https://github.com/DitchOoM/buffer/pull/266) — the pool OOM-recovery path described
  above.
- socket#217 — the JVM/Android read path honors the caller's configured `readBufferSize` instead of
  an oversized `SO_RCVBUF`-derived buffer, removing the worst-case allocation size that made this
  failure reachable in the first place (with Linux parity).
- websocket#19 — test helpers default to `BufferFactory.deterministic()` instead of pinning
  `BufferFactory.Default`, matching the library's own safe default.

## See Also

- [`ANDROID_ART_ALLOCATOR.md`](https://github.com/DitchOoM/buffer/blob/main/ANDROID_ART_ALLOCATOR.md) —
  ART's LOS/non-moving-space routing rules, the version/image-flavor matrix, and a local repro
  recipe.
- [Allocation Zones](./allocation-zones.md) — the full `BufferFactory` API and composable decorators.
- [Android Platform](../platforms/android.md) — `SharedMemory`, Parcelable support, and camera/video
  buffer pooling patterns.
- [Buffer Pooling](../recipes/buffer-pooling.md) — pool usage patterns and statistics.
