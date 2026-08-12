package com.ditchoom.buffer.codec

import com.ditchoom.buffer.FluentTextPolicy
import com.ditchoom.buffer.Utf8

/**
 * Context key carrying the [FluentTextPolicy] generated codecs use for `String` fields that
 * are not pinned by `@UseTextPolicy`.
 *
 * Resolution precedence in generated code: field `@UseTextPolicy` → message `@UseTextPolicy` →
 * this key → [DEFAULT_TEXT_POLICY].
 *
 * The key is bound to the fluent family on purpose: a generated linear encode/decode body has
 * no channel for a checked result, so `Utf8.Checked` cannot be injected — the type system
 * rejects it here exactly as the processor rejects it in `@UseTextPolicy`.
 */
object TextPolicyKey : CodecKey<FluentTextPolicy>

/**
 * The policy generated codecs fall back to when neither annotation nor context provides one:
 * [Utf8.Strict] — protocols never silently rewrite payloads; ill-formed text fails the
 * encode/decode loudly (and atomically) instead.
 */
val DEFAULT_TEXT_POLICY: FluentTextPolicy = Utf8.Strict

/**
 * Referenced from `@UseTextPolicy(policy = MyPolicyHolder::class)` for custom policies:
 * annotations cannot hold instances, so a Kotlin `object` implementing this interface names
 * the policy instead — the same pattern as `@UseCodec`'s codec objects.
 */
interface TextPolicyProvider {
    val policy: FluentTextPolicy
}
