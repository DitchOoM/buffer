@file:Suppress("ktlint:standard:filename")

package com.ditchoom.buffer.codec.processor.discovery

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import java.util.concurrent.atomic.AtomicReference

/**
 * Stand-alone PhaseA harness — compiles the supplied sources with KSP, runs only
 * [Discovery], and returns the captured [DiscoveryResult] without touching the
 * production processor pipeline. Per-kind tests use this to assert on the raw
 * symbol surface in isolation from PhaseB/C/D.
 *
 * The captured result is funneled through an [AtomicReference] keyed by a per-call
 * token because kctfork loads the processor in the host classloader; multiple
 * concurrent calls would otherwise race on a single static slot.
 */
internal data class DiscoveryHarnessResult(
    val exitCode: KotlinCompilation.ExitCode,
    val messages: String,
    val discovery: DiscoveryResult,
)

internal fun runDiscovery(vararg sources: SourceFile): DiscoveryHarnessResult {
    val sink = AtomicReference<DiscoveryResult?>(null)
    val provider = DiscoveryCaptureProvider(sink)
    val allSources = listOf(annotationSource) + sources.toList()
    val compilation =
        KotlinCompilation().apply {
            this.sources = allSources
            configureKsp(useKsp2 = true) {
                symbolProcessorProviders += provider
            }
            kotlincArguments = listOf("-Xskip-metadata-version-check")
        }
    val result = compilation.compile()
    val captured = sink.get() ?: DiscoveryResult(emptyList(), emptyList())
    return DiscoveryHarnessResult(result.exitCode, result.messages, captured)
}

private class DiscoveryCaptureProvider(
    private val sink: AtomicReference<DiscoveryResult?>,
) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = DiscoveryCaptureProcessor(sink, environment)
}

private class DiscoveryCaptureProcessor(
    private val sink: AtomicReference<DiscoveryResult?>,
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val result = Discovery.run(resolver)
        sink.set(result)
        for (diag in result.diagnostics) {
            if (diag.severity == DiscoveryDiagnostic.Severity.Error) {
                environment.logger.error(diag.message)
            } else {
                environment.logger.warn(diag.message)
            }
        }
        return emptyList()
    }
}

/**
 * Minimal annotation surface duplicated here so KSP resolves them from source rather
 * than the binary `buffer-codec` jar (which would force a Kotlin version match the
 * embedded compiler can't always honor). Mirrors the subset of Phase 1's
 * `Annotations.kt` that PhaseA actually inspects.
 */
internal val annotationSource: SourceFile =
    SourceFile.kotlin(
        "DiscoveryAnnotations.kt",
        """
    package com.ditchoom.buffer.codec.annotations

    enum class Endianness { Default, Big, Little }

    enum class Direction { Default, Codec, DecodeOnly, EncodeOnly }

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class ProtocolMessage(
        val wireOrder: Endianness = Endianness.Default,
        val direction: Direction = Direction.Default,
        val onUnknownDiscriminator: String = "",
    )

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class Decode

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class Encode

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class PacketType(val wire: Int)

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class DiscriminatorField

    @Target(AnnotationTarget.TYPE_PARAMETER)
    @Retention(AnnotationRetention.BINARY)
    annotation class Payload
    """,
    )
