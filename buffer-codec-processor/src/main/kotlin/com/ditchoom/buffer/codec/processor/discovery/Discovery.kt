package com.ditchoom.buffer.codec.processor.discovery

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/**
 * PhaseA — KSP-bound discovery.
 *
 * The only file in this package that imports KSP. Walks every `@ProtocolMessage`-annotated
 * declaration the resolver reports, classifies it by structural kind (data/value class,
 * object, sealed root), and produces a flat list of [RawSymbol] POJOs.
 *
 * Discovery does no semantic validation beyond structural sanity (sealed root needs at
 * least one subclass; `@ProtocolMessage` object cannot have type parameters; non-class
 * elements cannot be annotated). Everything else — annotation conflict detection, field
 * rule mutex, cross-symbol joins — lives in PhaseB and PhaseC.
 *
 * Errors produced here are [DiscoveryDiagnostic] entries on the returned [DiscoveryResult];
 * the caller (production: `ProtocolMessageProcessor`; tests: `DiscoveryTestProcessor`) is
 * responsible for surfacing them through the appropriate channel.
 */
object Discovery {
    private const val PROTOCOL_MESSAGE_FQN = "com.ditchoom.buffer.codec.annotations.ProtocolMessage"
    private const val DECODE_FQN = "com.ditchoom.buffer.codec.annotations.Decode"
    private const val ENCODE_FQN = "com.ditchoom.buffer.codec.annotations.Encode"
    private const val DISPATCH_ON_FQN = "com.ditchoom.buffer.codec.annotations.DispatchOn"
    private const val DISPATCH_VALUE_FQN = "com.ditchoom.buffer.codec.annotations.DispatchValue"
    private const val USE_CODEC_FQN = "com.ditchoom.buffer.codec.annotations.UseCodec"

    fun run(resolver: Resolver): DiscoveryResult {
        val diagnostics = mutableListOf<DiscoveryDiagnostic>()
        val symbols = mutableListOf<RawSymbol>()
        val visited = mutableSetOf<String>()

        for (annotated in resolver.getSymbolsWithAnnotation(PROTOCOL_MESSAGE_FQN)) {
            if (annotated !is KSClassDeclaration) {
                diagnostics +=
                    DiscoveryDiagnostic(
                        severity = DiscoveryDiagnostic.Severity.Error,
                        message =
                            "@ProtocolMessage can only be applied to classes or sealed interfaces, " +
                                "but was applied to a ${annotated::class.simpleName ?: "non-class element"}.",
                        sourceFqn = (annotated as? KSDeclaration)?.qualifiedName?.asString() ?: "",
                    )
                continue
            }
            visit(annotated, visited, symbols, diagnostics)
        }

        val externalClasses = collectExternalClasses(resolver, symbols)

        return DiscoveryResult(symbols.toList(), diagnostics.toList(), externalClasses)
    }

    /**
     * Collect directly-declared supertype metadata for every class FQN referenced from
     * `@DispatchOn(framing = X::class)` (sealed-root annotations), `@UseCodec(codec = X::class)`
     * (constructor-parameter annotations), and the **companion object** of every
     * `@DispatchOn(type = D::class)` discriminator. PhaseC consumes this map to validate
     * framer / codec conformance without reaching back into the KSP resolver — the only file
     * that imports KSP remains this one. PhaseB consumes the same map to auto-discover a
     * companion-object framer when `@DispatchOn(framing = ...)` is left at its `Inherit`
     * default, which is the path MQTT relies on (`MqttFixedHeader.Companion : BodyLengthFraming`).
     *
     * Only direct supertypes are captured. Transitive parents would require
     * `KSClassDeclaration.getAllSuperTypes()`, which returns unresolved type variables on
     * inherited generic supertypes — the bug that broke the previous BodyLengthFraming
     * attempt.
     */
    private fun collectExternalClasses(
        resolver: Resolver,
        symbols: List<RawSymbol>,
    ): Map<String, RawClassMetadata> {
        val candidateFqns = mutableSetOf<String>()
        // Slice 5.5: track which candidate FQNs are `@DispatchOn(type = D::class)`
        // discriminators so the metadata pass walks their properties for
        // `@DispatchValue` (which lives on a property declaration, not on a
        // constructor parameter — a derived getter cannot exist as a value-class
        // ctor parameter, so the legacy `getAllProperties()` walk is the only
        // surface that picks it up).
        val discriminatorTypeFqns = mutableSetOf<String>()
        for (s in symbols) {
            for (ann in s.annotations) {
                if (ann.fqn == DISPATCH_ON_FQN) {
                    val framingRef = ann.arguments["framing"] as? RawAnnotationValue.ClassRef
                    if (framingRef != null && framingRef.resolved && framingRef.fqn.isNotBlank()) {
                        candidateFqns += framingRef.fqn
                    }
                    val typeRef = ann.arguments["type"] as? RawAnnotationValue.ClassRef
                    if (typeRef != null && typeRef.resolved && typeRef.fqn.isNotBlank()) {
                        // Companion-object framer auto-discovery target. The companion may
                        // not exist (then resolution returns null below) or may not implement
                        // DispatchFraming (then PhaseB falls through to Unframed).
                        candidateFqns += "${typeRef.fqn}.Companion"
                        // Slice 5.5: track the discriminator FQN itself so PhaseA can walk
                        // its properties for `@DispatchValue` (covers derived getters that
                        // never appear as constructor parameters).
                        candidateFqns += typeRef.fqn
                        discriminatorTypeFqns += typeRef.fqn
                    }
                }
            }
            if (s is RawSymbol.DataLike) {
                for (p in s.constructorParameters) {
                    for (ann in p.annotations) {
                        if (ann.fqn != USE_CODEC_FQN) continue
                        val ref = ann.arguments["codec"] as? RawAnnotationValue.ClassRef ?: continue
                        if (ref.resolved && ref.fqn.isNotBlank()) candidateFqns += ref.fqn
                    }
                }
            }
        }
        if (candidateFqns.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, RawClassMetadata>()
        for (fqn in candidateFqns) {
            val decl = resolveCompanionAware(resolver, fqn) ?: continue
            val dispatchValueProperty =
                if (fqn in discriminatorTypeFqns) findDispatchValueProperty(decl) else null
            out[fqn] =
                RawClassMetadata(
                    fqn = fqn,
                    directlyDeclaredSupertypes = decl.superTypes.toList().map(::toRawTypeRef),
                    dispatchValueProperty = dispatchValueProperty,
                )
        }
        return out.toMap()
    }

    /**
     * Walk a discriminator class's declared properties looking for one annotated
     * `@DispatchValue`. Mirrors legacy `ProtocolMessageProcessor.resolveDispatchOn`
     * which uses `getAllProperties().filter { it.annotations.any { ... } }`.
     *
     * Returns `null` when no annotated property is found OR when more than one is
     * found (PhaseB surfaces the conflict via its existing `dispatchProps.size > 1`
     * check).
     */
    private fun findDispatchValueProperty(decl: KSClassDeclaration): RawDispatchValueProperty? {
        val matches =
            decl
                .getAllProperties()
                .filter { prop ->
                    prop.annotations.any { ann ->
                        ann.annotationFqnOrNull() == DISPATCH_VALUE_FQN
                    }
                }.toList()
        if (matches.size != 1) return null
        val prop = matches.first()
        val resolvedType = prop.type.resolve()
        val typeFqn =
            resolvedType.declaration.qualifiedName?.asString()
                ?: "kotlin.${resolvedType.declaration.simpleName.asString()}"
        return RawDispatchValueProperty(
            name = prop.simpleName.asString(),
            returnTypeFqn = typeFqn,
        )
    }

    /**
     * Resolve a class FQN, falling back for nested `Companion` references where KSP
     * needs the companion's actual simple name (which may be omitted in source). The
     * convention `${EnclosingFqn}.Companion` is what `@DispatchOn` callers see at the
     * annotation surface; KSP's `getClassDeclarationByName` accepts the same string when
     * the companion is named `Companion`. For named companions (rare in protocol code)
     * we fall through to the enclosing-class scan.
     */
    private fun resolveCompanionAware(
        resolver: Resolver,
        fqn: String,
    ): KSClassDeclaration? {
        val direct = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
        if (direct != null) return direct
        if (!fqn.endsWith(".Companion")) return null
        val enclosingFqn = fqn.removeSuffix(".Companion")
        val enclosing =
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(enclosingFqn))
                ?: return null
        return enclosing.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
    }

    private fun visit(
        decl: KSClassDeclaration,
        visited: MutableSet<String>,
        symbols: MutableList<RawSymbol>,
        diagnostics: MutableList<DiscoveryDiagnostic>,
    ) {
        val fqn = decl.qualifiedName?.asString() ?: return
        if (!visited.add(fqn)) return

        val annotations = decl.annotations.toList().map(::toRawAnnotation)
        val direction = directionOf(decl, annotations)
        val packageName = decl.packageName.asString()
        val enclosing = enclosingSimpleNames(decl)
        val simpleName = decl.simpleName.asString()

        when {
            Modifier.SEALED in decl.modifiers -> {
                val subclasses = decl.getSealedSubclasses().toList()
                if (subclasses.isEmpty()) {
                    diagnostics +=
                        DiscoveryDiagnostic(
                            severity = DiscoveryDiagnostic.Severity.Error,
                            message =
                                "Sealed ${if (decl.classKind == ClassKind.INTERFACE) "interface" else "class"} " +
                                    "'$simpleName' has no subclasses. Add at least one data class or object that " +
                                    "implements this sealed type.",
                            sourceFqn = fqn,
                        )
                    return
                }
                symbols +=
                    RawSymbol.SealedRoot(
                        fqn = fqn,
                        simpleName = simpleName,
                        packageName = packageName,
                        enclosingNames = enclosing,
                        annotations = annotations,
                        direction = direction,
                        subclassFqns = subclasses.mapNotNull { it.qualifiedName?.asString() },
                        typeParameters = decl.typeParameters.map(::toRawTypeParameter),
                    )
                for (sub in subclasses) {
                    visit(sub, visited, symbols, diagnostics)
                }
            }

            decl.classKind == ClassKind.OBJECT -> {
                if (decl.typeParameters.isNotEmpty()) {
                    diagnostics +=
                        DiscoveryDiagnostic(
                            severity = DiscoveryDiagnostic.Severity.Error,
                            message =
                                "@ProtocolMessage object '$simpleName' cannot have type parameters. " +
                                    "If you need @Payload, declare a data class instead.",
                            sourceFqn = fqn,
                        )
                    return
                }
                symbols +=
                    RawSymbol.ObjectSymbol(
                        fqn = fqn,
                        simpleName = simpleName,
                        packageName = packageName,
                        enclosingNames = enclosing,
                        annotations = annotations,
                        direction = direction,
                    )
            }

            else -> {
                val ctor = decl.primaryConstructor
                if (ctor == null) {
                    diagnostics +=
                        DiscoveryDiagnostic(
                            severity = DiscoveryDiagnostic.Severity.Error,
                            message =
                                "@ProtocolMessage class '$simpleName' must have a primary constructor with " +
                                    "val parameters. Fix: add a primary constructor (e.g., " +
                                    "'class $simpleName(val id: Int)').",
                            sourceFqn = fqn,
                        )
                    return
                }
                if (ctor.parameters.isEmpty()) {
                    diagnostics +=
                        DiscoveryDiagnostic(
                            severity = DiscoveryDiagnostic.Severity.Error,
                            message =
                                "@ProtocolMessage class '$simpleName' must have at least one val parameter " +
                                    "in its primary constructor. For a type-only message (no wire bytes), " +
                                    "declare it as 'object $simpleName' or 'data object $simpleName' instead.",
                            sourceFqn = fqn,
                        )
                    return
                }
                val kind =
                    when {
                        Modifier.DATA in decl.modifiers -> DataLikeKind.DataClass
                        Modifier.VALUE in decl.modifiers -> DataLikeKind.ValueClass
                        else -> DataLikeKind.RegularClass
                    }
                symbols +=
                    RawSymbol.DataLike(
                        fqn = fqn,
                        simpleName = simpleName,
                        packageName = packageName,
                        enclosingNames = enclosing,
                        annotations = annotations,
                        direction = direction,
                        classKind = kind,
                        typeParameters = decl.typeParameters.map(::toRawTypeParameter),
                        constructorParameters = ctor.parameters.map(::toRawCtorParameter),
                    )
            }
        }
    }

    private fun directionOf(
        decl: KSClassDeclaration,
        annotations: List<RawAnnotation>,
    ): RawDirection {
        val hasDecode = decl.annotations.any { it.annotationFqnOrNull() == DECODE_FQN }
        val hasEncode = decl.annotations.any { it.annotationFqnOrNull() == ENCODE_FQN }
        if (hasDecode && hasEncode) return RawDirection.Conflict
        if (hasDecode) return RawDirection.DecodeOnly
        if (hasEncode) return RawDirection.EncodeOnly

        val protocolMessage = annotations.find { it.fqn == PROTOCOL_MESSAGE_FQN } ?: return RawDirection.Default
        val dirArg = protocolMessage.arguments["direction"] as? RawAnnotationValue.EnumVal ?: return RawDirection.Default
        return when (dirArg.name) {
            "DecodeOnly" -> RawDirection.DecodeOnly
            "EncodeOnly" -> RawDirection.EncodeOnly
            "Codec" -> RawDirection.Codec
            else -> RawDirection.Default
        }
    }

    private fun toRawCtorParameter(param: KSValueParameter): RawCtorParameter =
        RawCtorParameter(
            name = param.name?.asString() ?: "",
            typeRef = toRawTypeRef(param.type),
            annotations = param.annotations.toList().map(::toRawAnnotation),
            hasDefault = param.hasDefault,
        )

    private fun toRawTypeParameter(tp: KSTypeParameter): RawTypeParameter {
        val upperBoundFqn =
            tp.bounds
                .firstOrNull()
                ?.resolve()
                ?.declaration
                ?.qualifiedName
                ?.asString()
        return RawTypeParameter(
            name = tp.name.asString(),
            upperBoundFqn = upperBoundFqn,
            annotations = tp.annotations.toList().map(::toRawAnnotation),
        )
    }

    private fun toRawTypeRef(typeRef: KSTypeReference): RawTypeRef {
        val resolved = typeRef.resolve()
        val decl = resolved.declaration
        val isTypeParameter = decl is KSTypeParameter
        val fqn = decl.qualifiedName?.asString() ?: ""
        val simple = decl.simpleName.asString()
        val isError = resolved.isError
        return RawTypeRef(
            fqn = fqn,
            name = simple,
            typeArguments = resolved.arguments.map(::toRawTypeArgument),
            isNullable = resolved.isMarkedNullable,
            isTypeParameter = isTypeParameter,
            resolved = !isError,
        )
    }

    private fun toRawTypeArgument(arg: KSTypeArgument): RawTypeRef {
        val typeRef = arg.type
        if (typeRef != null) return toRawTypeRef(typeRef)
        return RawTypeRef(
            fqn = "",
            name = "*",
            typeArguments = emptyList(),
            isNullable = false,
            isTypeParameter = false,
            resolved = false,
        )
    }

    private fun toRawAnnotation(ann: KSAnnotation): RawAnnotation {
        val fqn = ann.annotationFqnOrNull() ?: ""
        val args = mutableMapOf<String, RawAnnotationValue>()
        for (arg in ann.arguments) {
            val name = arg.name?.asString() ?: continue
            args[name] = toRawAnnotationValue(arg.value)
        }
        return RawAnnotation(fqn = fqn, arguments = args)
    }

    private fun toRawAnnotationValue(value: Any?): RawAnnotationValue =
        when (value) {
            null -> RawAnnotationValue.Unknown("null")
            is Boolean -> RawAnnotationValue.BoolVal(value)
            is Int -> RawAnnotationValue.IntVal(value)
            is Long -> RawAnnotationValue.LongVal(value)
            is String -> RawAnnotationValue.StringVal(value)
            is KSType -> classRefFromKsType(value)
            is KSName -> {
                val s = value.asString()
                val typeFqn = s.substringBeforeLast('.', missingDelimiterValue = "")
                val name = s.substringAfterLast('.')
                RawAnnotationValue.EnumVal(typeFqn = typeFqn, name = name)
            }
            is List<*> -> RawAnnotationValue.ListVal(value.map { toRawAnnotationValue(it) })
            is Array<*> -> RawAnnotationValue.ListVal(value.map { toRawAnnotationValue(it) })
            else -> {
                val classFqn = value::class.qualifiedName ?: value::class.toString()
                if (classFqn.endsWith(".KSTypeImpl") || value is KSDeclaration) {
                    val ksType = value as? KSType
                    if (ksType != null) {
                        classRefFromKsType(ksType)
                    } else {
                        fallbackEnumOrUnknown(value.toString())
                    }
                } else {
                    fallbackEnumOrUnknown(value.toString())
                }
            }
        }

    private fun classRefFromKsType(type: KSType): RawAnnotationValue.ClassRef {
        val fqn = type.declaration.qualifiedName?.asString() ?: ""
        return RawAnnotationValue.ClassRef(fqn = fqn, resolved = !type.isError && fqn.isNotEmpty())
    }

    /**
     * KSP encodes enum-valued annotation arguments inconsistently across versions —
     * sometimes a `KSType`, sometimes a `KSName`, sometimes a custom holder whose
     * `toString()` reads `pkg.Type.Constant`. Treat any dotted identifier with a
     * capitalized terminal segment as an enum reference; otherwise fall back to raw.
     */
    private fun fallbackEnumOrUnknown(raw: String): RawAnnotationValue {
        val dotIdx = raw.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < raw.length - 1) {
            val name = raw.substring(dotIdx + 1)
            val typeFqn = raw.substring(0, dotIdx)
            if (name.firstOrNull()?.isUpperCase() == true && typeFqn.isNotEmpty()) {
                return RawAnnotationValue.EnumVal(typeFqn = typeFqn, name = name)
            }
        }
        return RawAnnotationValue.Unknown(raw)
    }

    private fun KSAnnotation.annotationFqnOrNull(): String? =
        annotationType
            .resolve()
            .declaration
            .qualifiedName
            ?.asString()

    private fun enclosingSimpleNames(decl: KSClassDeclaration): List<String> {
        val names = mutableListOf<String>()
        var current: KSClassDeclaration? = decl
        while (current != null) {
            names.add(0, current.simpleName.asString())
            current = current.parentDeclaration as? KSClassDeclaration
        }
        return names
    }
}
