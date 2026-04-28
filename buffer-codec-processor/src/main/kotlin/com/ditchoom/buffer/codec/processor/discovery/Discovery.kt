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

        return DiscoveryResult(symbols.toList(), diagnostics.toList())
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
