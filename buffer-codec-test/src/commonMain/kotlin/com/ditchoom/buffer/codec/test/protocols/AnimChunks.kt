package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
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
 * Sealed interface with nested @PacketType variants — tests:
 * 1. Sub-codecs generated for nested classes (AnimChunkHeaderCodec, etc.)
 * 2. Qualified names in dispatch (is AnimChunk.Header, not is Header)
 * 3. Flattened codec names (AnimChunkHeaderCodec, not HeaderCodec)
 * 4. Mixed payload/non-payload variants in sealed dispatch
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
    data class ImageFrame<@Payload P>(
        @WireBytes(3) val index: UInt,
        val size: ImageSize,
        val bitmapLength: Int,
        @LengthFrom("bitmapLength") val bitmap: P,
    ) : AnimChunk

    @PacketType(0x03)
    data class Metadata(
        @LengthPrefixed val name: String,
        @LengthPrefixed val author: String,
    ) : AnimChunk
}
