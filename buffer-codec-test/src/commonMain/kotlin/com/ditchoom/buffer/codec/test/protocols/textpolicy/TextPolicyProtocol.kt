package com.ditchoom.buffer.codec.test.protocols.textpolicy

import com.ditchoom.buffer.FluentTextPolicy
import com.ditchoom.buffer.Utf8
import com.ditchoom.buffer.codec.TextPolicyProvider
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseTextPolicy

/** Provider-referenced policy: annotations cannot hold instances, objects name them. */
object LenientHolder : TextPolicyProvider {
    override val policy: FluentTextPolicy = Utf8.Lenient
}

/**
 * Exercises every `@UseTextPolicy` resolution tier at runtime:
 * [topic] pins Strict (field), [note] pins Lenient via a provider (field),
 * [payload] is unpinned — message default (none here) → context key → Utf8.Strict.
 */
@ProtocolMessage
data class TextPolicyMessage(
    @UseTextPolicy(Utf8.Strict::class)
    @LengthPrefixed val topic: String,
    @UseTextPolicy(LenientHolder::class)
    @LengthPrefixed val note: String,
    @LengthPrefixed val payload: String,
)
