package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Opaque, platform-typed bitmap handle — the canonical "decode into a
 * platform-native value" shape from the buffer-codec lockdown plan (Pattern
 * #1 + #2 combined): the typed handle stores a **consumer-owned
 * [PlatformBuffer]** holding pixel bytes, allocated by the codec via a
 * caller-supplied [BufferFactory] and copied with `dst.write(source)`. No
 * intermediate `ByteArray` is materialized along the decode path.
 *
 * **Why this shape**: with `Bitmap(val nativeBitmap: PlatformBitmap) :
 * Payload`, the KSP transitive `Payload`-shape walk (Change 1) descends
 * into `Bitmap`, looks at the property `nativeBitmap: PlatformBitmap`,
 * finds it is not a forbidden type, not `Payload`, not a `value class` —
 * and stops. The walker treats `PlatformBitmap` as an opaque typed value,
 * exactly as a platform-native handle should be treated. Whatever the
 * platform actual stores internally is invisible to the rule.
 *
 * Test-fixture stand-in: real consumers (Android client, Compose desktop
 * app, etc.) would back this with `android.graphics.Bitmap` /
 * `BufferedImage` / `Skia.Image` / `web.images.ImageBitmap` and have the
 * codec hand the buffer's `toNativeData()` to the platform decoder. The
 * lockdown invariant holds the same way — the Payload's public shape
 * carries no raw bytes; the bytes live inside the platform-native handle.
 */
expect class PlatformBitmap

/**
 * Construct from a consumer-owned [PlatformBuffer]. The platform actual
 * takes ownership of [pixels] and is responsible for its lifetime; do not
 * use [pixels] after handing it off.
 *
 * Named `bitmapFrom` rather than `PlatformBitmap` to avoid a constructor /
 * top-level-fun ambiguity in the actuals (where the class's primary
 * constructor must hold the pixel buffer, and an overload-with-same-name
 * top-level fun would clash with it).
 */
expect fun bitmapFrom(
    width: Int,
    height: Int,
    pixels: PlatformBuffer,
): PlatformBitmap

expect fun PlatformBitmap.bitmapWidth(): Int

expect fun PlatformBitmap.bitmapHeight(): Int

/**
 * Returns a [ReadBuffer] view over the pixel storage. Position is reset to
 * 0 before each call so the view is iterable from the start.
 */
expect fun PlatformBitmap.bitmapPixels(): ReadBuffer

expect fun PlatformBitmap.bitmapPixelSize(): Int

expect fun PlatformBitmap.bitmapEquals(other: PlatformBitmap): Boolean

expect fun PlatformBitmap.bitmapHashCode(): Int

/**
 * Test convenience: wraps [pixels] into a fresh buffer via
 * [testFixtureFactory] and forwards to the `(width, height, PlatformBuffer)`
 * constructor.
 */
fun platformBitmapOf(
    width: Int,
    height: Int,
    pixels: ByteArray,
): PlatformBitmap {
    val buf = testFixtureFactory.allocate(pixels.size)
    buf.writeBytes(pixels)
    buf.resetForRead()
    return bitmapFrom(width, height, buf)
}
