# Detekt Static-Analysis Burndown Plan

Discovery + organization for eliminating ALL suppressed detekt findings (no
suppression) on the `buffer` Kotlin Multiplatform repo. This is the planning
artifact only — no fixes are applied here.

- **Branch / worktree:** `detekt/burndown` at `/Users/thebehera/git/buffer-detekt`
- **Config:** root `build.gradle.kts` applies `dev.detekt` to all modules,
  `buildUponDefaultConfig = true`, per-module baselines at
  `<module>/config/detekt/baseline.xml`, `ignoreFailures = true`.
- **Inventory source of truth:** the 10 committed `baseline.xml` files
  (1015 `<ID>` entries total).
- **Structural line numbers:** obtained by temporarily disabling the baseline,
  running `./gradlew detektAll` (non-blocking), and parsing the Checkstyle
  reports at `<module>/build/reports/detekt/detekt.xml`. The build.gradle.kts
  edit was reverted; the worktree is clean except this file.

---

## 1. Summary

### Grand total: **1015 suppressed findings**, 20 rules, 10 modules.

### Count by rule (descending)

| # | Rule | Count | Bucket |
|--:|------|------:|--------|
| 1 | MagicNumber | 435 | MECHANICAL |
| 2 | MaxLineLength | 145 | MECHANICAL |
| 3 | ReturnCount | 136 | MECHANICAL* |
| 4 | TooManyFunctions | 61 | STRUCTURAL |
| 5 | CyclomaticComplexMethod | 57 | STRUCTURAL |
| 6 | LongMethod | 53 | STRUCTURAL |
| 7 | MatchingDeclarationName | 36 | MECHANICAL |
| 8 | UnusedParameter | 18 | MECHANICAL |
| 9 | TooGenericExceptionCaught | 17 | CORRECTNESS |
| 10 | SwallowedException | 13 | CORRECTNESS |
| 11 | LargeClass | 11 | STRUCTURAL |
| 12 | LoopWithTooManyJumpStatements | 10 | CORRECTNESS |
| 13 | TooGenericExceptionThrown | 8 | CORRECTNESS |
| 14 | ThrowsCount | 5 | CORRECTNESS |
| 15 | EmptyFunctionBlock | 4 | MECHANICAL |
| 16 | NestedBlockDepth | 2 | STRUCTURAL |
| 17 | ComplexCondition | 1 | STRUCTURAL |
| 18 | MayBeConstant | 1 | MECHANICAL |
| 19 | ForbiddenComment | 1 | MECHANICAL |
| 20 | InvalidPackageDeclaration | 1 | MECHANICAL |

`*` ReturnCount is bucketed MECHANICAL but flagged as debatable — see §2.

### Count by module (descending)

| Module | Count |
|--------|------:|
| buffer | 442 |
| buffer-codec-processor | 205 |
| buffer-crypto | 202 |
| buffer-compression | 79 |
| buffer-codec-test | 29 |
| buffer-1brc | 25 |
| buffer-codec | 22 |
| buffer-flow | 7 |
| buffer-codec-gradle-plugin | 2 |
| buffer-codec-schema | 2 |

### Module × rule matrix

Blank = 0. Abbreviations: Magic=MagicNumber, MaxLn=MaxLineLength,
RetCnt=ReturnCount, TooManyFn=TooManyFunctions, Cyclo=CyclomaticComplexMethod,
LongM=LongMethod, MatchDecl=MatchingDeclarationName, UnusedP=UnusedParameter,
GenCaught=TooGenericExceptionCaught, Swallow=SwallowedException,
LargeCls=LargeClass, LoopJmp=LoopWithTooManyJumpStatements,
GenThrow=TooGenericExceptionThrown, ThrCnt=ThrowsCount, EmptyFn=EmptyFunctionBlock,
Nested=NestedBlockDepth, CmplxCond=ComplexCondition, MayConst=MayBeConstant,
Forbid=ForbiddenComment, InvPkg=InvalidPackageDeclaration.

| module | Magic | MaxLn | RetCnt | TooManyFn | Cyclo | LongM | MatchDecl | UnusedP | GenCaught | Swallow | LargeCls | LoopJmp | GenThrow | ThrCnt | EmptyFn | Nested | CmplxCond | MayConst | Forbid | InvPkg |
|--------|------:|------:|-------:|----------:|------:|------:|----------:|--------:|----------:|--------:|---------:|--------:|---------:|-------:|--------:|-------:|----------:|---------:|-------:|-------:|
| buffer | 272 | 28 | 49 | 32 | 3 | 5 | 7 | 2 | 11 | 10 | 6 | 2 | 8 | 1 | 3 | 1 |  |  | 1 | 1 |
| buffer-codec-processor | 26 | 26 | 58 | 7 | 41 | 41 |  |  |  |  | 3 | 2 |  |  |  |  | 1 |  |  |  |
| buffer-crypto | 80 | 46 | 11 | 12 | 4 | 1 | 25 | 10 | 2 | 3 |  | 2 |  | 4 |  | 1 |  | 1 |  |  |
| buffer-compression | 24 | 20 | 13 | 7 | 1 | 1 |  | 4 | 4 |  | 2 | 3 |  |  |  |  |  |  |  |  |
| buffer-codec-test |  | 13 | 3 |  | 6 | 5 | 1 |  |  |  |  | 1 |  |  |  |  |  |  |  |  |
| buffer-1brc | 16 | 2 | 1 |  | 2 |  | 1 | 2 |  |  |  |  |  |  |  | 1 |  |  |  |  |
| buffer-codec | 11 | 8 |  | 1 |  |  | 2 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| buffer-flow | 5 | 1 |  | 1 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| buffer-codec-gradle-plugin |  | 1 | 1 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| buffer-codec-schema | 1 |  |  | 1 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |

### ktlint `max_line_length`

There is **no `.editorconfig`** in the repo (confirmed via `git ls-files`).
The ktlint Gradle plugin (`org.jlleitschuh.gradle.ktlint` 14.2.0, wrapping
ktlint 1.x) is configured per-module with `android.set(true)` and no explicit
override, so ktlint's effective `max_line_length` is **100** (Android code
style default).

**However**, the 145 `MaxLineLength` findings here come from **detekt's own
rule**, not ktlint. With `buildUponDefaultConfig = true` and no custom detekt
config, detekt's `MaxLineLength` threshold is its default of **120**. So when
wrapping these lines, the binding constraint for detekt-zero is **120**, but to
also satisfy ktlint, wrap to **100**. **Recommendation: wrap to ≤100** so both
tools are green in one pass.

---

## 2. Category buckets

Reasoning for the split: MECHANICAL = the fix is a deterministic, local,
behavior-preserving edit that a bulk pass can apply with a tight verify loop.
CORRECTNESS = local edit, but the *right* edit requires reading what the code
is supposed to do (which exception, why it's swallowed). STRUCTURAL = the rule
is satisfied only by reshaping methods/classes, which risks perf regressions
and behavior changes in this perf-sensitive library — human-reviewed, not
auto-fixed.

### MECHANICAL — 641 findings (bulk-fixable, low risk)

| Rule | Count | Fix strategy | Effort | Risk | Batchable? |
|------|------:|--------------|:------:|:----:|:----------:|
| MagicNumber | 435 | Extract to named `const val` / companion consts. Most are in tests (buffer alone: 272, heavily in `*Test`/`*Benchmark`). For test data, hoist to per-file `private const` or shared test fixtures. For main source, name semantic constants (e.g. byte widths, masks, UTF-8 boundaries). | L (volume) | low | bulk, per-file |
| MaxLineLength | 145 | Wrap to ≤100 cols (satisfies both detekt-120 and ktlint-100). Prefer `ktlintFormat` first, then hand-wrap residue. | M | low | bulk; `ktlintFormat` does most |
| ReturnCount* | 136 | detekt default max = 2. Reduce via guard clauses / early-return consolidation / extracting a `when`. **Debatable bucket** — see note below. | M | low–med | mostly bulk; per-finding judgment in hot paths |
| MatchingDeclarationName | 36 | Rename the file to match its single top-level declaration, OR rename the declaration. 25 are in buffer-crypto, 7 in buffer. Mind expect/actual filename pairing on MPP. | S | low | bulk |
| UnusedParameter | 18 | Remove the parameter, or prefix `_`, or `@Suppress` only if it's an interface/override contract. 10 in buffer-crypto, 4 in buffer-compression. Check overrides/actuals before deleting. | S | low | per-finding (override check) |
| EmptyFunctionBlock | 4 | Add explanatory comment `// no-op` body or `Unit`, or remove if truly dead. All in buffer (slice/auto buffers). | S | low | bulk |
| MayBeConstant | 1 | `val` → `const val`. buffer-crypto. | S | low | bulk |
| ForbiddenComment | 1 | Resolve/remove the `TODO` in `BufferBaselineBenchmark.kt`. | S | low | single |
| InvalidPackageDeclaration | 1 | Fix package decl in `Parcelable.kt` (androidMain) to match dir. | S | low | single |

**ReturnCount debatable-bucket note:** classified MECHANICAL because the
canonical fix (guard clauses) is local and usually behavior-preserving. BUT
58 of 136 are in `buffer-codec-processor` (KSP validators/emitters with many
early-exit `return`s that are clearer as-is) and several are in native
hot-path buffer code where collapsing returns can defeat early-out
optimizations. **Treat ReturnCount in hot-path/native files (see §3) and in the
codec-processor as per-finding judgment, not blind bulk.** The remainder
(tests, simple helpers) is safely bulk.

### CORRECTNESS — 53 findings (localized, needs judgment)

| Rule | Count | Fix strategy | Effort | Risk | Batchable? |
|------|------:|--------------|:------:|:----:|:----------:|
| TooGenericExceptionCaught | 17 | Replace `catch (e: Exception)`/`Throwable` with the specific type(s) actually thrown; rethrow/wrap the rest. 11 in buffer, 4 in buffer-compression, 2 in buffer-crypto. | M | med | per-finding |
| SwallowedException | 13 | Either log/wrap the caught exception into the thrown one (preserve cause) or document why it's intentionally dropped. 10 in buffer, 3 in buffer-crypto. | M | med | per-finding |
| LoopWithTooManyJumpStatements | 10 | Refactor `break`/`continue`/`return`-heavy loops; extract loop body to a function returning a sentinel. Several are in `StreamingStringDecoder` (UTF-8 decode hot path) — verify byte-exact behavior. | M | med | per-finding |
| TooGenericExceptionThrown | 8 | Replace `throw Exception(...)`/`RuntimeException` with a domain exception type. All 8 in buffer. | S | low–med | per-finding |
| ThrowsCount | 5 | detekt default max = 2 thrown points. Consolidate validation throws, or introduce a single `require`/`check` helper. 4 in buffer-crypto, 1 in buffer. | S | low–med | per-finding |

CORRECTNESS is small (53) and concentrated in `buffer`, `buffer-compression`,
`buffer-crypto`. Exception-related rules in buffer-crypto are
**security-sensitive** — never silently broaden/swallow in crypto paths.

### STRUCTURAL — 185 baseline / 186 live findings (human-reviewed, NOT auto-fixed)

> The live `detektAll` run reports **186** structural findings vs **185** in the
> baselines (one extra `TooManyFunctions`: `BaseWebBuffer.kt:7`, 35 functions,
> in `webMain` — a source set the baseline appears to have under-counted). The
> per-finding table below is the authoritative live list with precise
> `file:line` + enclosing declaration.

| Rule | Count (baseline) | Why human-only | Effort | Risk |
|------|-----------------:|----------------|:------:|:----:|
| TooManyFunctions | 61 | Buffer/codec classes legitimately implement large interfaces (`ReadBuffer` 62 fns, `WriteBuffer` 54 fns by design). Splitting breaks the unified buffer API. | L | high |
| CyclomaticComplexMethod | 57 | Mostly KSP analyzers/emitters and UTF-8/crypto DER parsers — complexity is intrinsic to the wire-format logic. | L | high |
| LongMethod | 53 | Same files; splitting codec emit/decode functions risks correctness & generated-output drift (snapshot tests). | L | high |
| LargeClass | 11 | Large test suites + `BaseJvmBuffer`/`CodecEmitter`/`ProtocolMessageProcessor`. | M–L | med–high |
| NestedBlockDepth | 2 | `JvmBuffer.createFromParcel` (Parcelable IPC), `HkdfCore.expandInto` (crypto KDF hot loop). | M | med |
| ComplexCondition | 1 | `ProtocolMessageProcessor.kt:2197` — one boolean condition; could split but trivial/low value. | S | low |

#### Full STRUCTURAL per-finding list (`module · file:line · enclosing decl · rule`)

The complexity/length/count number from detekt is appended in parentheses where
available.

**buffer**
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/StreamingStringDecoder.apple.kt:216` · fun `decodeUtf8CodePointToAppendable` · CyclomaticComplexMethod (23)
- buffer · `buffer/src/jsMain/kotlin/com/ditchoom/buffer/JsBuffer.kt:310` · fun `xorMaskCopy` · CyclomaticComplexMethod (17)
- buffer · `buffer/src/linuxMain/kotlin/com/ditchoom/buffer/StreamingStringDecoder.linux.kt:211` · fun `decodeUtf8CodePointToAppendable` · CyclomaticComplexMethod (23)
- buffer · `buffer/src/commonTest/kotlin/com/ditchoom/buffer/BufferComparisonTest.kt:12` · class `BufferComparisonTest` · LargeClass
- buffer · `buffer/src/commonTest/kotlin/com/ditchoom/buffer/BufferPoolTests.kt:25` · class `BufferPoolTests` · LargeClass
- buffer · `buffer/src/commonTest/kotlin/com/ditchoom/buffer/BufferStreamTests.kt:20` · class `BufferStreamTests` · LargeClass
- buffer · `buffer/src/commonTest/kotlin/com/ditchoom/buffer/BufferTests.kt:12` · class `BufferTests` · LargeClass
- buffer · `buffer/src/commonTest/kotlin/com/ditchoom/buffer/WrapperTransparencyTests.kt:21` · class `WrapperTransparencyTests` · LargeClass
- buffer · `buffer/src/jvmCommonMain/kotlin/com/ditchoom/buffer/BaseJvmBuffer.kt:9` · class `BaseJvmBuffer` · LargeClass
- buffer · `buffer/src/jsMain/kotlin/com/ditchoom/buffer/JsBuffer.kt:310` · fun `xorMaskCopy` · LongMethod (65)
- buffer · `buffer/src/jvmCommonMain/kotlin/com/ditchoom/buffer/BaseJvmBuffer.kt:484` · fun `xorMaskCopy` · LongMethod (61)
- buffer · `buffer/src/jvmTest/kotlin/com/ditchoom/buffer/FastDirectByteBufferTest.kt:228` · fun `byte order conversion performance` · LongMethod (71)
- buffer · `buffer/src/jvmTest/kotlin/com/ditchoom/buffer/SimdByteSwapTest.kt:151` · fun `bulk byte swap operations performance` · LongMethod (81)
- buffer · `buffer/src/linuxTest/kotlin/com/ditchoom/buffer/WriteStringBenchmarkTest.kt:24` · fun `benchmarkWriteStringApproaches` · LongMethod (69)
- buffer · `buffer/src/androidMain/kotlin/com/ditchoom/buffer/JvmBuffer.kt:85` · fun `createFromParcel` · NestedBlockDepth
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/MutableDataBuffer.kt:51` · class `MutableDataBuffer` · TooManyFunctions (50)
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/MutableDataBuffer.kt:706` · class `MutableDataBufferSlice` · TooManyFunctions (39)
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/NSDataBuffer.kt:39` · class `NSDataBuffer` · TooManyFunctions (27)
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/NSDataBuffer.kt:275` · class `NSDataBufferSlice` · TooManyFunctions (24)
- buffer · `buffer/src/appleMain/kotlin/com/ditchoom/buffer/UnsafeMemory.apple.kt:18` · object `UnsafeMemory` · TooManyFunctions (12)
- buffer · `buffer/src/appleTest/kotlin/com/ditchoom/buffer/NSDataBufferTest.kt:17` · class `NSDataBufferTest` · TooManyFunctions (19)
- buffer · `buffer/src/commonBenchmark/kotlin/com/ditchoom/buffer/benchmark/BufferBaselineBenchmark.kt:30` · class `BufferBaselineBenchmark` · TooManyFunctions (13)
- buffer · `buffer/src/commonBenchmark/kotlin/com/ditchoom/buffer/benchmark/BulkOperationsBenchmark.kt:36` · class `BulkOperationsBenchmark` · TooManyFunctions (27)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/BufferComparison.kt:1` · file `BufferComparison.kt` · TooManyFunctions (12)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/ReadBuffer.kt:52` · interface `ReadBuffer` · TooManyFunctions (62)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/UnsafeMemory.kt:30` · object `UnsafeMemory` · TooManyFunctions (12)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/WriteBuffer.kt:50` · interface `WriteBuffer` · TooManyFunctions (54)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/pool/PooledBuffer.kt:23` · class `PooledBuffer` · TooManyFunctions (55)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/AutoFillingSuspendingStreamProcessor.kt:28` · class `AutoFillingSuspendingStreamProcessor` · TooManyFunctions (19)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/BufferStream.kt:110` · interface `StreamProcessor` · TooManyFunctions (19)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/BufferStream.kt:268` · class `DefaultStreamProcessor` · TooManyFunctions (26)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/SuspendingStreamProcessor.kt:25` · interface `SuspendingStreamProcessor` · TooManyFunctions (18)
- buffer · `buffer/src/commonMain/kotlin/com/ditchoom/buffer/stream/SuspendingStreamProcessor.kt:141` · class `SyncToSuspendingProcessor` · TooManyFunctions (18)
- buffer · `buffer/src/jsMain/kotlin/com/ditchoom/buffer/JsBuffer.kt:15` · class `JsBuffer` · TooManyFunctions (24)
- buffer · `buffer/src/jsMain/kotlin/com/ditchoom/buffer/UnsafeMemory.js.kt:3` · object `UnsafeMemory` · TooManyFunctions (13)
- buffer · `buffer/src/jvmCommonMain/kotlin/com/ditchoom/buffer/BaseJvmBuffer.kt:9` · class `BaseJvmBuffer` · TooManyFunctions (50)
- buffer · `buffer/src/jvmCommonMain/kotlin/com/ditchoom/buffer/UnsafeMemory.jvmCommon.kt:7` · object `UnsafeMemory` · TooManyFunctions (15)
- buffer · `buffer/src/jvmCommonTest/kotlin/com/ditchoom/buffer/NativeDataConversionJvmTest.kt:10` · class `NativeDataConversionJvmTest` · TooManyFunctions (16)
- buffer · `buffer/src/linuxMain/kotlin/com/ditchoom/buffer/NativeBuffer.kt:57` · class `NativeBuffer` · TooManyFunctions (54)
- buffer · `buffer/src/linuxMain/kotlin/com/ditchoom/buffer/NativeBuffer.kt:724` · class `NativeBufferSlice` · TooManyFunctions (40)
- buffer · `buffer/src/linuxMain/kotlin/com/ditchoom/buffer/StreamingStringDecoder.linux.kt:34` · class `LinuxStreamingStringDecoder` · TooManyFunctions (12)
- buffer · `buffer/src/linuxMain/kotlin/com/ditchoom/buffer/UnsafeMemory.linux.kt:18` · object `UnsafeMemory` · TooManyFunctions (12)
- buffer · `buffer/src/nonJvmMain/kotlin/com/ditchoom/buffer/ByteArrayBuffer.kt:13` · class `ByteArrayBuffer` · TooManyFunctions (44)
- buffer · `buffer/src/wasmJsMain/kotlin/com/ditchoom/buffer/LinearBuffer.kt:97` · class `LinearBuffer` · TooManyFunctions (25)
- buffer · `buffer/src/wasmJsMain/kotlin/com/ditchoom/buffer/MemoryManager.kt:70` · object `LinearMemoryAllocator` · TooManyFunctions (13)
- buffer · `buffer/src/wasmJsMain/kotlin/com/ditchoom/buffer/UnsafeMemory.wasmJs.kt:40` · object `UnsafeMemory` · TooManyFunctions (13)
- buffer · `buffer/src/webMain/kotlin/com/ditchoom/buffer/BaseWebBuffer.kt:7` · class `BaseWebBuffer` · TooManyFunctions (35) — *live-only, not in baseline*

**buffer-1brc**
- buffer-1brc · `buffer-1brc/src/jvmMain/kotlin/com/ditchoom/onebrc/Main.kt:14` · fun `main` · CyclomaticComplexMethod (18)
- buffer-1brc · `buffer-1brc/src/nativeMain/kotlin/com/ditchoom/onebrc/Main.native.kt:13` · fun `main` · CyclomaticComplexMethod (16)

**buffer-codec**
- buffer-codec · `buffer-codec/src/commonMain/kotlin/com/ditchoom/buffer/codec/GrowableWriteBuffer.kt:28` · class `GrowableWriteBuffer` · TooManyFunctions (21)

**buffer-codec-processor** (largest structural concentration; KSP analyzers/emitters)
- `.../ProtocolMessageProcessor.kt:2197` · ComplexCondition (4)
- `.../CodecAnalyzer.kt:46` · fun `analyze` · CyclomaticComplexMethod (44) + LongMethod (160)
- `.../CodecAnalyzer.kt:308` · fun `detectSealedDispatchOnParentDiscriminator` · CyclomaticComplexMethod (27)
- `.../CodecAnalyzer.kt:469` · fun `analyzeField` · CyclomaticComplexMethod (62) + LongMethod (279)
- `.../CodecAnalyzer.kt:872` · fun `analyzeValueClassScalarField` · CyclomaticComplexMethod (16) + LongMethod (77)
- `.../CodecAnalyzer.kt:1029` · fun `analyzeLengthSource` · CyclomaticComplexMethod (23)
- `.../CodecAnalyzer.kt:1656` · fun `analyzeLengthPrefixedListSpec` · CyclomaticComplexMethod (16)
- `.../CodecAnalyzer.kt:1964` · fun `parseWhenExpression` · CyclomaticComplexMethod (15)
- `.../CodecAnalyzer.kt:2041` · fun `resolveDottedCondition` · CyclomaticComplexMethod (16)
- `.../CodecAnalyzer.kt:2481` · fun `analyzeSealedDispatcher` · CyclomaticComplexMethod (18) + LongMethod (83)
- `.../CodecAnalyzer.kt:2614` · fun `analyzeDispatchOnSealedDispatcher` · CyclomaticComplexMethod (45) + LongMethod (173)
- `.../CodecAnalyzer.kt:2936` · fun `classifyVariantWireSize` · CyclomaticComplexMethod (31)
- `.../CodecAnalyzer.kt:1` · file `CodecAnalyzer.kt` · TooManyFunctions (57)
- `.../CodecEmitter.kt:119` · class `CodecEmitter` · LargeClass + TooManyFunctions (40)
- `.../CodecEmitter.kt:131` · fun `tryEmit` · CyclomaticComplexMethod (16)
- `.../CodecEmitter.kt:760` · fun `appendDecodeField` · CyclomaticComplexMethod (17)
- `.../CodecEmitter.kt:842` · fun `appendEncodeField` · CyclomaticComplexMethod (17)
- `.../CodecEmitter.kt:952` · fun `coalesceBatches` · CyclomaticComplexMethod (16)
- `.../CodecEmitter.kt:1057` · fun `appendBatchedDecode` · LongMethod (63)
- `.../CodecEmitter.kt:1357` · fun `encodeToBatchAccumulator` · CyclomaticComplexMethod (19)
- `.../CodecEmitter.kt:1455` · fun `buildPartialClassTypeSpec` · LongMethod (76)
- `.../CodecEmitter.kt:1662` · fun `buildPartialEntryFun` · LongMethod (81)
- `.../CodecEmitter.kt:1791` · fun `partialFieldTypeName` · CyclomaticComplexMethod (17)
- `.../CodecEmitterDispatch.kt:1` · file · TooManyFunctions (24)
- `.../CodecEmitterDispatch.kt:93` · fun `buildDispatchDecodeFun` · LongMethod (85)
- `.../CodecEmitterDispatch.kt:245` · fun `buildDispatchEncodeFun` · LongMethod (72)
- `.../CodecEmitterDispatch.kt:351` · fun `buildDispatchWireSizeFun` · LongMethod (74)
- `.../CodecEmitterDispatch.kt:461` · fun `buildDispatchPeekFun` · LongMethod (155)
- `.../CodecEmitterDispatch.kt:664` · fun `buildDispatchFileSpec` · LongMethod (77)
- `.../CodecEmitterDispatch.kt:819` · fun `buildFramedByDispatchOnPeekFun` · LongMethod (94)
- `.../CodecEmitterDispatch.kt:974` · fun `buildDispatchOnDecodeAggregatingFun` · LongMethod (86)
- `.../CodecEmitterDispatch.kt:1279` · fun `appendPeekFixedScalar` · CyclomaticComplexMethod (27) + LongMethod (88)
- `.../CodecEmitterFields.kt:1` · file · TooManyFunctions (52)
- `.../CodecEmitterFields.kt:56` · fun `appendNaturalReadWithSwap` · CyclomaticComplexMethod (15) + LongMethod (66)
- `.../CodecEmitterFields.kt:126` · fun `appendManualScalarDecode` · CyclomaticComplexMethod (20)
- `.../CodecEmitterFields.kt:273` · fun `appendManualScalarEncode` · CyclomaticComplexMethod (20)
- `.../CodecEmitterFields.kt:470` · fun `appendDecodeConditional` · LongMethod (130)
- `.../CodecEmitterFields.kt:647` · fun `appendEncodeConditional` · LongMethod (79)
- `.../CodecEmitterFields.kt:753` · fun `appendConditionalScalarSwapDecode` · CyclomaticComplexMethod (17) + LongMethod (89)
- `.../CodecEmitterPeek.kt:1` · file · TooManyFunctions (18)
- `.../CodecEmitterPeek.kt:80` · fun `buildPeekFrameFun` · CyclomaticComplexMethod (28) + LongMethod (133)
- `.../CodecEmitterPeek.kt:342` · fun `appendPeekUseCodecScalar` · LongMethod (64)
- `.../CodecEmitterPeek.kt:442` · fun `appendPeekBoundingDynamicPrior` · LongMethod (80)
- `.../CodecEmitterPeek.kt:637` · fun `appendPeekLengthPrefixedUseCodecList` · LongMethod (65)
- `.../CodecEmitterPeek.kt:734` · fun `appendSequentialPeek` · CyclomaticComplexMethod (20) + LongMethod (128)
- `.../CodecEmitterPeek.kt:1122` · fun `appendPeekScalar` · CyclomaticComplexMethod (15) + LongMethod (61)
- `.../CodecEmitterWireSize.kt:17` · fun `buildWireSizeFun` · CyclomaticComplexMethod (32) + LongMethod (155)
- `.../CodecSchemaDescriptor.kt:43` · object `CodecSchemaDescriptor` · TooManyFunctions (13)
- `.../CodecSchemaDescriptor.kt:111` · fun `describeField` · CyclomaticComplexMethod (17)
- `.../ProtocolMessageProcessor.kt:103` · class `ProtocolMessageProcessor` · LargeClass + TooManyFunctions (30)
- `.../ProtocolMessageProcessor.kt:109` · fun `process` · CyclomaticComplexMethod (23) + LongMethod (79)
- `.../ProtocolMessageProcessor.kt:218` · fun `validateSealedDispatcher` · CyclomaticComplexMethod (15) + LongMethod (63)
- `.../ProtocolMessageProcessor.kt:312` · fun `validateDispatchOnSealed` · CyclomaticComplexMethod (44) + LongMethod (176)
- `.../ProtocolMessageProcessor.kt:526` · fun `validateRemainingBytesTrailers` · CyclomaticComplexMethod (16)
- `.../ProtocolMessageProcessor.kt:607` · fun `validateRemainingBytesElementType` · CyclomaticComplexMethod (21) + LongMethod (61)
- `.../ProtocolMessageProcessor.kt:739` · fun `validateWhen` · CyclomaticComplexMethod (49) + LongMethod (160)
- `.../ProtocolMessageProcessor.kt:974` · fun `validateAdjacentLengthFrom` · CyclomaticComplexMethod (18)
- `.../ProtocolMessageProcessor.kt:1049` · fun `validateLengthFrom` · CyclomaticComplexMethod (47) + LongMethod (152)
- `.../ProtocolMessageProcessor.kt:1255` · fun `validateUseCodec` · CyclomaticComplexMethod (41) + LongMethod (154)
- `.../ProtocolMessageProcessor.kt:1450` · fun `validateLengthPrefixedUseCodec` · CyclomaticComplexMethod (26) + LongMethod (113)
- `.../ProtocolMessageProcessor.kt:1717` · fun `validateFramedBy` · CyclomaticComplexMethod (31) + LongMethod (139)
- `.../ProtocolMessageProcessor.kt:1911` · fun `validateForwardCompatible` · CyclomaticComplexMethod (23) + LongMethod (140)
- `.../ProtocolMessageProcessor.kt:2097` · fun `validatePayloadTypeParameter` · LongMethod (68)
- `.../ProtocolMessageProcessor.kt:2182` · fun `implementsCodecOf` · CyclomaticComplexMethod (15)
- `.../ProtocolMessageProcessor.kt:2215` · fun `walkType` · CyclomaticComplexMethod (17)

**buffer-codec-schema**
- `.../CodecSchemaClassifier.kt:44` · object `CodecSchemaClassifier` · TooManyFunctions (12)

**buffer-codec-test**
- `.../mqttv5/V5PropertyBag.kt:111` · fun `approximateCount` · CyclomaticComplexMethod (26)
- `.../mqttv5/V5PropertyBag.kt:141` · fun `isEmpty` · CyclomaticComplexMethod (27)
- `.../mqttv5/V5PropertyBag.kt:192` · fun `toV5PropertyBag` · CyclomaticComplexMethod (30) + LongMethod (165)
- `.../mqttv5/V5PropertyBag.kt:392` · fun `decode` · CyclomaticComplexMethod (56) + LongMethod (182)
- `.../mqttv5/V5PropertyBag.kt:616` · fun `computeBodyByteCount` · CyclomaticComplexMethod (28)
- `.../snapshot/CodecSnapshotTest.kt:53` · test fun `generated codec output matches checked-in baselines` · CyclomaticComplexMethod (16) + LongMethod (61)
- `.../mqtt/MqttSubAckCodecTest.kt:369` · test fun `dataObjectVariantsSelfFrameDiscriminatorByte` · LongMethod (78)
- `.../mqttv5/V5PropertyBagCodecTest.kt:58` · test fun `roundTripsBagWithEveryUniqueVariantPopulated` · LongMethod (71)

**buffer-compression**
- `.../JvmCommonStreamingCompression.kt:692` · fun `parseOptionalGzipFields` · CyclomaticComplexMethod (16)
- `.../CompressionStressTests.kt:25` · class `CompressionStressTests` · LargeClass
- `.../StreamingCompressionTests.kt:13` · class `StreamingCompressionTests` · LargeClass
- `.../StreamProcessorIntegrationTests.kt:466` · test fun `interleavedReadAndAppendWithSuspendingProcessor` · LongMethod (62)
- `.../benchmark/StreamingCompressionBenchmark.kt:37` · class `StreamingCompressionBenchmark` · TooManyFunctions (18)
- `.../Compression.kt:1` · file · TooManyFunctions (12)
- `.../StreamProcessorExtensions.kt:121` · class `SuspendingDecompressingStreamProcessor` · TooManyFunctions (19)
- `.../StreamingCompression.kt:1` · file · TooManyFunctions (13)
- `.../jsAndWasmJsMain/.../JsInterop.kt:1` · file · TooManyFunctions (23)
- `.../jsMain/.../JsInteropActual.kt:1` · file · TooManyFunctions (36)
- `.../JvmCommonStreamingCompression.kt:1` · file · TooManyFunctions (14)
- `.../wasmJsMain/.../JsInteropActual.kt:3` · file · TooManyFunctions (53)

**buffer-crypto**
- `.../commonTest/.../HpkeKatTest.kt:62` · fun `runVector` · CyclomaticComplexMethod (19) + LongMethod (71)
- `.../commonTest/.../SignatureWycheproof.kt:31` · fun `run` · CyclomaticComplexMethod (22)
- `.../jvmCommonMain/.../Signatures.jvmCommon.kt:286` · fun `parseCanonicalEcdsaDer` · CyclomaticComplexMethod (27)
- `.../linuxMain/.../Signatures.linux.kt:99` · fun `isCanonicalEcdsaDer` · CyclomaticComplexMethod (24)
- `.../commonMain/.../HkdfCore.kt:65` · fun `expandInto` · NestedBlockDepth
- `.../appleMain/.../Aead.apple.kt:3` · file · TooManyFunctions (12)
- `.../appleMain/.../KeyAgreement.apple.kt:3` · file · TooManyFunctions (14)
- `.../appleMain/.../Signatures.apple.kt:3` · file · TooManyFunctions (16)
- `.../commonMain/.../Aead.kt:1` · file · TooManyFunctions (19)
- `.../commonMain/.../HpkeContext.kt:1` · file · TooManyFunctions (18)
- `.../jsAndWasmJsMain/.../Aead.jsAndWasmJs.kt:1` · file · TooManyFunctions (14)
- `.../jsAndWasmJsMain/.../Signatures.jsAndWasmJs.kt:1` · file · TooManyFunctions (18)
- `.../jvmCommonMain/.../Aead.jvmCommon.kt:1` · file · TooManyFunctions (18)
- `.../jvmCommonMain/.../KeyAgreement.jvmCommon.kt:1` · file · TooManyFunctions (22)
- `.../jvmCommonMain/.../Signatures.jvmCommon.kt:1` · file · TooManyFunctions (22)
- `.../linuxMain/.../Aead.linux.kt:3` · file · TooManyFunctions (15)
- `.../linuxMain/.../Signatures.linux.kt:3` · file · TooManyFunctions (14)

**buffer-flow**
- `.../benchmark/FlowExtensionsBenchmark.kt:38` · class `FlowExtensionsBenchmark` · TooManyFunctions (16)

---

## 3. Performance-sensitive flags

CLAUDE.md's performance guidance is explicit: on Apple/Native avoid object
allocation in hot paths, use pointer arithmetic and `memcpy`; SIMD
auto-vectorization on native depends on simple loop shapes; JVM/JS rely on bulk
ops. Any "fix" that restructures these can defeat the optimizer or change
byte-exact behavior. Flag list below.

**Total perf-flagged: 211 findings** = 120 MagicNumber/ReturnCount in hot-path
main-source files + 91 STRUCTURAL findings in hot-path/SIMD/codec-emit/crypto
files. **All STRUCTURAL findings in §2 are already human-only; the table below
calls out the subset where a refactor is most dangerous.**

### MagicNumber / ReturnCount in hot-path code (do NOT bulk; review individually)

| Module | Rule | File | # | Why sensitive |
|--------|------|------|--:|---------------|
| buffer | MagicNumber | `BufferComparison.kt` | 4 | SIMD/bulk `contentEquals`/`mismatch`; constants are shift/word-size masks |
| buffer | MagicNumber | `JsBuffer.kt` | 7 | `xorMaskCopy`/bulk JS paths |
| buffer | MagicNumber | `LinearBuffer.kt` | 13 | WASM bump allocator + 256MB preallocation magic |
| buffer | MagicNumber | `NativeBuffer.kt` | 4 | Linux native pointer/malloc paths |
| buffer | MagicNumber | `ByteArrayBuffer.kt` | 6 | shared non-JVM hot path |
| buffer | MagicNumber | `BaseJvmBuffer.kt` | 2 | direct ByteBuffer xorMask |
| buffer | MagicNumber | `MutableDataBuffer.kt` / `NSDataBuffer.kt` | 4 / 4 | Apple NSData pointer paths |
| buffer | MagicNumber | `StreamingStringDecoder.{apple,linux,jvmCommon}.kt` | 10 / 10 / 3 | UTF-8 decode boundaries (0x80, 0xC0, 0xE0…) — name them, don't reshape |
| buffer | MagicNumber | `UnsafeMemory.wasmJs.kt` | 5 | unsafe memory ops |
| buffer-codec | MagicNumber | `GrowableWriteBuffer.kt` | 2 | growth-factor constants in batch encode |
| buffer-codec-processor | MagicNumber | `CodecEmitter.kt` | 5 | batch-coalescing thresholds |
| buffer-crypto | MagicNumber | `Aead.kt` / `Aead.jsAndWasmJs.kt` / `Hpke.kt` | 1 / 1 / 2 | nonce/tag/key sizes — must stay byte-exact |
| buffer | ReturnCount | `BufferComparison.kt` | 6 | early-out short-circuits in compare/search — collapsing returns can defeat fast-path |
| buffer | ReturnCount | `NativeBuffer.kt` | 6 | native fast paths |
| buffer | ReturnCount | `MutableDataBuffer.kt` | 5 | Apple fast paths |
| buffer | ReturnCount | `StreamingStringDecoder.{apple,linux,jvmCommon,wasmJs}.kt` | 5 / 4 / 1 / 1 | UTF-8 decode early-outs |
| buffer | ReturnCount | `JsBuffer.kt` / `LinearBuffer.kt` / `BaseJvmBuffer.kt` / `NSDataBuffer.kt` | 2 / 1 / 1 / 1 | per-platform buffer fast paths |
| buffer-codec-processor | ReturnCount | `CodecEmitter.kt` | 4 | emitter branch dispatch (clearer as multiple returns) |

MagicNumber in these files is safe to *name* (extract to `const val`) — that is
zero-runtime-cost and the recommended fix. It is the **ReturnCount** and all
**STRUCTURAL** reshaping in these files that carries perf/correctness risk.

### STRUCTURAL findings in the most perf/correctness-critical code

- `JsBuffer.xorMaskCopy` (LongMethod 65 + Cyclo 17) and `BaseJvmBuffer.xorMaskCopy`
  (LongMethod 61) — WebSocket frame masking hot path; CLAUDE.md calls xorMask
  SIMD-accelerated. Do not split blindly.
- `StreamingStringDecoder.{apple,linux}.decodeUtf8CodePointToAppendable`
  (Cyclo 23) and the `LoopWithTooManyJumpStatements` in `StreamingStringDecoder`
  — byte-exact UTF-8 decode; any refactor must pass the charset-parity tests.
- `HkdfCore.expandInto` (NestedBlockDepth) — crypto KDF inner loop; security +
  perf sensitive.
- `Signatures.{jvmCommon,linux}` ECDSA DER parsers (Cyclo 24–27) — security
  parsing; complexity is intrinsic, prefer leaving as-is or extracting helpers
  with exhaustive Wycheproof coverage.
- All `CodecEmitter*` / `ProtocolMessageProcessor` / `CodecAnalyzer` structural
  findings — these emit/validate generated code; `CodecSnapshotTest` pins the
  generated output. Any refactor must keep snapshots byte-identical.
- `V5PropertyBag.decode` (Cyclo 56 / LongMethod 182) — MQTT v5 wire decode;
  round-trip tests must stay green.

---

## 4. Recommended fan-out plan for the FIX phase

**Unit of parallelism: one agent per MODULE**, each owning that module's entire
MECHANICAL + CORRECTNESS set across all its source sets, verifying with
`./gradlew :<module>:detekt` to zero (after removing/regenerating that module's
baseline) plus the module's tests. STRUCTURAL is excluded from agents and goes
to the human queue (§2 list).

### Why per-module (not per-rule)
- detekt runs and verifies per-module (`:<module>:detekt`), and baselines are
  per-module — a module agent gets a clean, independent verify loop.
- Avoids cross-agent merge conflicts: each agent edits only its module's files
  (the rare shared-constant case is handled once up front, see below).
- MagicNumber dominates and is 90%+ concentrated in `buffer`,
  `buffer-codec-processor`, `buffer-crypto`, `buffer-compression` — those four
  agents carry the load; the small modules finish fast.

### Phase ordering
1. **Phase A — shared groundwork (single agent, first, blocking):** run
   `./gradlew ktlintFormat` repo-wide to clear most `MaxLineLength` (145) and
   normalize wrapping to ≤100, then re-run detekt to capture residue. Create any
   cross-cutting constants (below). This unblocks parallel agents from fighting
   over formatting.
2. **Phase B — per-module MECHANICAL (parallel, 10 agents):** each agent fixes
   its module's MagicNumber, MatchingDeclarationName, UnusedParameter,
   EmptyFunctionBlock, MayBeConstant, ForbiddenComment, InvalidPackageDeclaration,
   and the *safe* (non-hot-path) ReturnCount. Heavy modules (`buffer`,
   `buffer-codec-processor`, `buffer-crypto`, `buffer-compression`) can be
   sub-split by source set if needed.
3. **Phase C — per-module CORRECTNESS (parallel, same agents or specialists):**
   exception rules + LoopWithTooManyJumpStatements + ThrowsCount. buffer-crypto
   correctness should be a security-aware agent (never broaden/swallow crypto
   exceptions). Hot-path ReturnCount handled here, case-by-case.
4. **Phase D — STRUCTURAL (human):** work the §2 list, validated by the existing
   snapshot/round-trip/charset-parity/Wycheproof tests and benchmarks before/after.

### Verify loop per agent
```
# after fixes, regenerate (or delete) the module baseline so nothing is masked:
./gradlew :<module>:detektBaseline   # then ensure baseline.xml is empty/removed
./gradlew :<module>:detekt           # must report 0 (for the rules in scope)
./gradlew :<module>:allTests         # or :test for JVM-only modules
./gradlew :<module>:ktlintCheck
```
Final gate after all agents: `./gradlew detektAll` + `./gradlew allTests` +
`./gradlew ktlintCheck`. Then delete the now-empty baselines and flip
`ignoreFailures` to `false` so detekt enforces zero going forward.

### Cross-cutting constants/helpers (create ONCE, in Phase A)
- A shared **internal UTF-8 boundary constants** object for the
  `StreamingStringDecoder.*` actuals (0x80, 0xC0, 0xE0, 0xF0, continuation
  masks). These exact magic numbers repeat across appleMain/linuxMain/jvmCommon.
  Put in `commonMain` (`internal object Utf8` or top-level `const val`s) so all
  actuals reuse them — fixes ~23 MagicNumber findings consistently and avoids
  per-platform drift.
- **Byte-width / word-size constants** (8, 4, 2, shift counts) used across the
  buffer implementations — a single internal `BufferConstants` in `commonMain`
  (or `nonJvmMain` where native-shared). Be careful: many "magic" 8s are
  `Long.SIZE_BYTES` etc. — prefer existing stdlib `SIZE_BYTES`/`SIZE_BITS`
  constants over new ones where they apply (zero-cost, self-documenting).
- **Crypto sizes** (nonce/tag/key lengths) in buffer-crypto — likely already
  have named constants; reuse them rather than inventing new ones. Per-module,
  not cross-module.
- Everything else (test magic numbers, growth factors) is **per-module/per-file**
  — do NOT over-centralize; test fixtures should stay local to their test file.

---

## Appendix: reproduce the inventory
```
# total
grep -rh '<ID>' --include=baseline.xml . | grep -v /build/ | wc -l   # 1015
# structural line numbers (temporarily comment out baseline.set in root build.gradle.kts):
./gradlew detektAll        # ignoreFailures=true, won't fail
# parse <module>/build/reports/detekt/detekt.xml (Checkstyle); then revert build.gradle.kts
```
