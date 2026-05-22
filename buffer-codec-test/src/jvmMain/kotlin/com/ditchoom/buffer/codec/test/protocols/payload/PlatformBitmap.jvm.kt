package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.bufferHashCode

/**
 * JVM actual for [PlatformBitmap]. Test-fixture shape: stores a
 * consumer-owned [PlatformBuffer] alongside width/height. Real consumers
 * would back this with `BufferedImage` / `android.graphics.Bitmap` and
 * hand the buffer's `toNativeData()` to the platform decoder.
 */
actual class PlatformBitmap internal constructor(
    internal val w: Int,
    internal val h: Int,
    internal val pixelBuffer: PlatformBuffer,
)

actual fun bitmapFrom(
    width: Int,
    height: Int,
    pixels: PlatformBuffer,
): PlatformBitmap = PlatformBitmap(width, height, pixels)

actual fun PlatformBitmap.bitmapWidth(): Int = w

actual fun PlatformBitmap.bitmapHeight(): Int = h

actual fun PlatformBitmap.bitmapPixels(): ReadBuffer {
    pixelBuffer.position(0)
    pixelBuffer.setLimit(pixelBuffer.capacity)
    return pixelBuffer
}

actual fun PlatformBitmap.bitmapPixelSize(): Int = pixelBuffer.capacity

actual fun PlatformBitmap.bitmapEquals(other: PlatformBitmap): Boolean {
    if (w != other.w || h != other.h) return false
    pixelBuffer.position(0)
    pixelBuffer.setLimit(pixelBuffer.capacity)
    other.pixelBuffer.position(0)
    other.pixelBuffer.setLimit(other.pixelBuffer.capacity)
    return pixelBuffer.contentEquals(other.pixelBuffer)
}

actual fun PlatformBitmap.bitmapHashCode(): Int {
    pixelBuffer.position(0)
    pixelBuffer.setLimit(pixelBuffer.capacity)
    return (w * 31 + h) * 31 + bufferHashCode(pixelBuffer)
}
