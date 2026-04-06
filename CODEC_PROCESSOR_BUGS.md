# Codec Processor Bugs

Found during MQTT v5 Property sealed interface implementation.

## Bug 1: PayloadContextGenerator skips empty context classes

**File:** `buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/PayloadContextGenerator.kt`

**Problem:** When a `@ProtocolMessage` class has ONLY `@Payload` fields (no non-payload fields), `PayloadContextGenerator` returns early without generating the context class. But the generated codec still references it, causing an `Unresolved reference` compilation error.

**Reproduction:**
```kotlin
@ProtocolMessage
data class BinaryData<@Payload D>(
    @LengthPrefixed val data: D,  // only field, and it's @Payload
)
```
Generates `BinaryDataCodec` referencing `BinaryDataContext`, but `BinaryDataContext` is never generated.

**Root cause (line ~29):**
```kotlin
val nonPayloadFields = fields.filter { it.strategy !is FieldReadStrategy.PayloadField }
if (nonPayloadFields.isEmpty()) return  // ← skips generation, but codec still references it
```

**Fix:** Generate an empty context class instead of returning early. Or don't reference the context class in the codec when there are no non-payload fields.

**Workaround:** Add a non-payload field (e.g., explicit length with `@LengthFrom`):
```kotlin
@ProtocolMessage
data class BinaryData<@Payload D>(
    val length: UShort,                 // non-payload field
    @LengthFrom("length") val data: D, // payload bounded by length
)
```

---

## Bug 2: SealedDispatchGenerator doesn't handle @Payload variants with @DispatchOn

**File:** `buffer-codec-processor/src/main/kotlin/com/ditchoom/buffer/codec/processor/SealedDispatchGenerator.kt`

**Problem:** When a `@DispatchOn` sealed interface has a mix of `@Payload` and non-Payload variants, the generated `Codec<T>.decode(buffer)` method calls the @Payload variant's codec without the required decode lambda, causing a compilation error.

**Reproduction:**
```kotlin
@DispatchOn(MyDiscriminator::class)
@ProtocolMessage
sealed interface MyProtocol {
    @PacketType(1) @ProtocolMessage @JvmInline
    value class Simple(val x: UInt) : MyProtocol

    @PacketType(2) @ProtocolMessage
    data class WithPayload<@Payload D>(val len: UShort, @LengthFrom("len") val data: D) : MyProtocol
}
```

**Generated (incorrect):**
```kotlin
override fun decode(buffer: ReadBuffer): MyProtocol {
    val type = buffer.readByte().toInt() and 0xFF
    return when (type) {
        1 -> SimpleCodec.decode(buffer)
        2 -> WithPayloadCodec.decode(buffer)  // ERROR: missing lambda parameter
    }
}
```

**Expected:** The `Codec<T>.decode(buffer, context)` override should use `decodeFromContext(buffer, context)` for @Payload variants, looking up the decode lambda from `DecodeContext` keys. The non-context `decode(buffer)` should either throw or require context.

**Note:** Without `@DispatchOn`, the sealed dispatch generator correctly generates both Convention 1 (explicit lambdas) and Convention 2 (context-based). The bug is specific to the combination of `@DispatchOn` + mixed `@Payload` variants.

**Workaround:** Don't use the generated dispatch codec. Use the generated sub-codecs directly with a thin manual dispatch (~30 lines).
