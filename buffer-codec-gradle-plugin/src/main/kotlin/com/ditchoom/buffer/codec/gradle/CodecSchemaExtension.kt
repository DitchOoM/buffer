package com.ditchoom.buffer.codec.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `com.ditchoom.buffer.codec-schema` plugin (SCHEMA_DRIFT.md).
 *
 * ```kotlin
 * codecSchema {
 *     baseline.set(file("src/codecSchema/codec-schema.txt"))
 *     failOnBreaking.set(false)   // default — warn only
 * }
 * ```
 */
abstract class CodecSchemaExtension {
    /**
     * The baselined descriptor checked into source control. Defaults to
     * `src/codecSchema/codec-schema.txt`. `checkCodecSchema` diffs the freshly-generated descriptor
     * against this file; `updateCodecSchema` overwrites it. When it does not yet exist,
     * `checkCodecSchema` creates it from the generated descriptor and asks you to commit it.
     */
    abstract val baseline: RegularFileProperty

    /**
     * When `true`, a [DriftSeverity.BREAKING][com.ditchoom.buffer.codec.schema.DriftSeverity.BREAKING]
     * delta fails the build instead of only warning. Advisory (rename) deltas always warn and never
     * fail. Defaults to `false` so adoption never blocks a build out of the box.
     */
    abstract val failOnBreaking: Property<Boolean>
}
