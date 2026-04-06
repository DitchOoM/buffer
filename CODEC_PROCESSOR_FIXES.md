# Codec Processor Fixes Needed for socket-quic

## Context

The `socket-quic` module uses `@ProtocolMessage` + KSP to generate codecs for QUIC wire formats (ALPN encoding, transport parameters). The generated code triggers ktlint violations that can't be filtered out by the ktlint-gradle plugin when KSP-generated source directories are registered alongside handwritten sources.

## Fix 1: Emit `@file:Suppress("ktlint")` in generated files

**File:** `buffer-codec-processor/src/main/kotlin/.../CodecGenerator.kt`

**Problem:** Generated codec files use 2-space indentation, single-line compound statements, and backing properties without `private` — all of which violate ktlint's default rules. The ktlint-gradle plugin (v14) doesn't reliably exclude KSP-generated files from its worker process even when `filter { exclude("**/generated/**") }` is configured.

**Fix:** Add `@file:Suppress("ktlint")` as the first annotation on every generated file:

```kotlin
// In CodecGenerator, when building the FileSpec:
FileSpec.builder(packageName, codecName)
    .addAnnotation(
        AnnotationSpec.builder(Suppress::class)
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            .addMember("%S", "ktlint")
            .build()
    )
    // ... rest of the file
```

This is the standard approach for generated code — KotlinPoet supports file-level annotations.

## Fix 2: Use 4-space indentation in generated code

**File:** `buffer-codec-processor/src/main/kotlin/.../CodecGenerator.kt`

**Problem:** KotlinPoet defaults to 2-space indentation. ktlint requires 4-space.

**Fix:** Set `indent("    ")` on the `FileSpec.Builder`:

```kotlin
FileSpec.builder(packageName, codecName)
    .indent("    ")  // 4-space indent
```

## Fix 3: Break long encode/sizeOf lines

**Problem:** The generated `sizeOf` and `encode` functions put all field operations on a single line, exceeding ktlint's 140-char limit.

**Fix:** Use KotlinPoet's `addStatement` per field instead of building a single expression.

## Impact

These fixes would allow `socket-quic` to use `@ProtocolMessage` for:
- `AlpnProtocol` — ALPN wire format (RFC 7301)
- QUIC transport parameters (RFC 9000 §18.2) — future
- QUIC frame headers — future sealed dispatch

Without these fixes, `socket-quic` must either:
1. Skip KSP and manually encode (current workaround)
2. Disable ktlint rules project-wide via `.editorconfig`
