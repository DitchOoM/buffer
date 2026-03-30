package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireBytes
import kotlin.jvm.JvmInline

@JvmInline
value class ImageSize(
    val packed: UInt,
) {
    val width: UShort get() = (packed shr 16).toUShort()
    val height: UShort get() = packed.toUShort()

    companion object {
        fun of(
            width: UShort,
            height: UShort,
        ): ImageSize = ImageSize((width.toUInt() shl 16) or height.toUInt())
    }
}

/**
 * Sealed interface with nested @PacketType variants — tests that the KSP processor
 * auto-generates sub-codecs for nested classes and uses qualified names in the dispatch.
 *
 * Validates three fixes:
 * 1. Sub-codecs (AnimChunkHeaderCodec, etc.) are generated for nested variants
 * 2. Dispatch uses qualified names (is AnimChunk.Header, not is Header)
 * 3. Codec names are flattened (AnimChunkHeaderCodec, not HeaderCodec)
 */
@ProtocolMessage
sealed interface AnimChunk {
    @PacketType(0x01)
    data class Header(
        val magic: Int,
        val version: UByte,
        @WireBytes(3) val frameCount: UInt,
    ) : AnimChunk

    @PacketType(0x02)
    data class FrameInfo(
        @WireBytes(3) val index: UInt,
        val size: ImageSize,
        val bitmapOffset: Int,
        val bitmapLength: Int,
    ) : AnimChunk

    @PacketType(0x03)
    data class Metadata(
        @LengthPrefixed val name: String,
        @LengthPrefixed val author: String,
    ) : AnimChunk
}
