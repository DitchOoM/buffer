# Android/ART Direct-Buffer Allocation — Findings & OOM Repro Reference

## Current status — shipped

The fix is **merged and released**:

- **#255** — size-class buckets (`BufferSizeClass` power-of-two rounding + per-class buckets
  in `LockFreeBufferPool` / `SingleThreadedBufferPool`) that stop the Android ART
  fragmentation OOM (commonMain, factory-agnostic), plus native-memory lifecycle hardening
  (`String.toReadBuffer` defaults to `managed()`; `ReadBuffer.indexOf(String)` frees its
  staging needle in a `finally`). Tests: `BufferPoolFragmentationTests` (commonTest),
  `NonMovableOomRegressionTest` (androidInstrumentedTest), `PoolChurnBenchmark`.
- **#267** — the ART fragmentation / factory-difference explainer docs.

The factory-routing approach (`case2`) was **rejected** — the rationale is in the
"Implications for the fix" section below. The rest of this document is standing ART
reference material (allocation mechanics, OOM-message decoding, repro recipes).

---

Reference notes from reproducing the Autobahn case 9.2.5 large-message OOM
(websocket CI run `28618587934`, job `84868487414`, 2026-07-02). Everything here was
verified empirically on emulators; keep this document updated if ART behavior changes
in future API levels or Mainline trains.

## How ART allocates `ByteBuffer.allocateDirect`

On Android, `allocateDirect(n)` → `DirectByteBuffer$MemoryRef` →
`VMRuntime.newNonMovableArray(byte.class, n)`. The backing memory is a Java `byte[]`
in the managed heap (it counts against the app's heap growth limit), pinned so it
never moves. Where that array lands depends on its size:

| Request size | Space | Properties |
|---|---|---|
| < 12 KiB (LOS threshold) | **non-moving malloc space** | dlmalloc-style, fixed ~60.7 MB capacity, never compacted — fragments permanently |
| ≥ 12 KiB | **Large Object Space (LOS)** | freelist over a reserved region; holes reclaim/coalesce, but the region itself can fragment |

**Critical fallback rule:** when an LOS allocation fails, ART retries the allocation
in the non-moving space. The `OutOfMemoryError` message describes the *fallback*
space — which is why the CI crash for an 8 MiB buffer reports `malloc_space
fragmentation ... capacity = 60678144` even though 8 MiB buffers normally never live
there.

## Reading the OOM message

```
Failed to allocate a 8388637 byte allocation with 25149440 free bytes and 182MB until OOM,
target footprint 437558088, growth limit 603979776;
failed due to malloc_space fragmentation
(largest possible contiguous allocation 516800 bytes, space in use 49924680 bytes, capacity = 60678144)
```

- `growth limit` — the app's heap budget (`dalvik.vm.heapgrowthlimit`, or `heapsize`
  with `largeHeap`). CI: 576 MB. Stock emulator: 192 MB.
- `capacity` / `space in use` / `largest possible contiguous` — stats of the space the
  final attempt ran in. `~60.7 MB capacity` identifies the non-moving space.
- "free bytes" ≫ "largest possible contiguous" is the fragmentation signature: memory
  is free but no contiguous run fits the request.

## Version / image-flavor matrix (observed 2026-07-02)

| Image | Behavior of large direct buffers | Repro? |
|---|---|---|
| android-34 **default** (AOSP) | LOS + non-moving fallback as above | **YES** (CI's image) |
| android-34 google_apis | growth-limit OOMs only; no fragmentation failures observed | no |
| android-35 google_apis | 256 MB fill produced zero OOMs (appears native-backed) | no |
| android-36 google_apis | counts against heap; ART self-heals (OOM thrown internally, GC retry succeeds ~20 ms later) | no |
| android-26 google_apis | large buffers verifiably in LOS (`255(240MB) LOS objects` freed by GC) | no |

Take-away: the failure depends on the **ART module version** (stock AOSP vs
Mainline-updated google_apis images), not simply the API level. Real-world devices
that don't receive ART Mainline updates remain affected regardless of OS age.

## The actual CI failure mechanism (two fronts, both required)

1. **LOS front:** 49 Autobahn cases churn MB-scale, odd-sized message buffers
   (`LockFreeBufferPool.acquire` misses allocate exact sizes like 8 MiB + 29). The
   LOS freelist region fragments until an 8 MiB contiguous run no longer exists.
   This is only reachable when the heap budget (576 MB in CI) is large enough to
   drive the LOS region toward exhaustion — under the stock 192 MB emulator limit
   the region always retains a huge untouched tail and the failure is unreachable.
2. **Non-moving front:** the stream-processor read path churns 8 KiB pool buffers
   (below the LOS threshold), fragmenting the ~60 MB non-moving space (CI: 47 MB in
   use, largest contiguous 505 KB).
3. The 8 MiB request fails in the LOS, falls back to the non-moving space, fails
   there too → `OutOfMemoryError`, process crash.

## Reproducing locally (30 s instead of the 26-minute CI loop)

Test: `buffer/src/androidInstrumentedTest/kotlin/com/ditchoom/buffer/NonMovableOomRegressionTest.kt`
- `case2_fragmentedSpaces_largeDefaultAllocationSucceeds` — deterministic two-front
  repro; asserts the desired behavior, so it is **red until the fix lands**.
- `case1_pooledLargeMessageChurn_finalLargeAcquireSucceeds` — pool-shaped churn;
  stays green at this scale (CI needed 22 min of churn), kept as an end-to-end gate.

Setup (WSL2/Linux, exact CI environment):

```bash
# 1. Exact CI image + AVD
yes | sdkmanager "system-images;android-34;default;x86_64"
echo no | avdmanager create avd -n api34def_oom -k "system-images;android-34;default;x86_64" -f
emulator -avd api34def_oom -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &

# 2. CI heap geometry — REQUIRED; emulator -prop silently ignores dalvik.* props.
#    default-flavor images are rootable; must be re-applied after every emulator boot.
adb root
adb shell "setprop dalvik.vm.heapgrowthlimit 576m; setprop dalvik.vm.heapsize 576m; stop; start"

# 3. Run (note: this module's only instrumented variant is `benchmark`;
#    there is no connectedDebugAndroidTest task)
./gradlew :buffer:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.buffer.NonMovableOomRegressionTest
```

Local red-run signature (matches CI byte-for-byte in structure):

```
Failed to allocate a 8388656 byte allocation with 25165824 free bytes and 177MB until OOM,
target footprint 443534656, growth limit 603979776; failed due to malloc_space fragmentation
(largest possible contiguous allocation 83568 bytes, space in use 39430464 bytes, capacity = 60698624)
```

## Validating allocator behavior in tests

- `adb logcat` GC lines report LOS bytes explicitly: `Explicit concurrent copying GC
  freed 948(32KB) AllocSpace objects, 188(176MB) LOS objects, ...` — the second pair
  tells you whether your buffers landed in the LOS.
- `Throwing OutOfMemoryError` lines appear in logcat even when ART recovers (API 36
  retries internally); a passing test does not mean no OOM was thrown.
- `getprop dalvik.vm.heapgrowthlimit` / `heapsize` — verify heap geometry before
  trusting a green fragmentation test.

## Implications for the fix (pool fix landed 2026-07-02; factory routing rejected)

- **Pool (commonMain, factory-agnostic) — LANDED, the fix:** `BufferSizeClass`
  power-of-two rounding + per-class buckets in `LockFreeBufferPool` /
  `SingleThreadedBufferPool` stops *both* churn streams (exact odd-size large buffers
  → LOS front; 8 KiB acquire/release → non-moving front) on every platform. Live
  block sizes are bounded to <= 31 size classes, so neither freelist can fragment.
  `NonMovableOomRegressionTest.case1` (pool-shaped churn) pins this red→green.
- **Factory (androidMain) — IMPLEMENTED, THEN REJECTED:** routing
  `BufferFactory.Default` allocations >= 12 KiB to an owning native-malloc buffer
  (`OwnedNativeJvmBuffer`: Unsafe malloc + JNI `NewDirectByteBuffer`) takes both ART
  spaces out of the picture for large buffers. Rejected because it silently changes
  the Default-factory lifecycle contract: every large buffer becomes a leak-on-drop
  `CloseableBuffer`, and no GC safety net can make that safe on ART — derived
  ByteBuffer views (`duplicate()`/`slice()`/`asReadOnlyBuffer()`, incl.
  `toNativeData()` handles) share only the internal `MemoryRef` and do NOT retain
  the original buffer (unlike OpenJDK's `att` chain), so a Cleaner referent can
  become unreachable while a live view still addresses the memory — premature free
  (corruption) is strictly worse than the leak it would prevent. Given the narrow
  affected window (stock non-Mainline ART + largeHeap-scale budget + sustained
  mixed-size churn) and that the pool fix removes the churn mechanism itself, the
  contract change wasn't worth it. `NonMovableOomRegressionTest.case2` — the
  deterministic held-pin repro that only factory routing can satisfy — is kept
  `@Ignore`d as the repro recipe if this is ever revisited. Note for any revisit:
  `wrapNativeAddress` is non-owning (`DirectJvmBuffer.freeNativeMemory()` is a
  no-op); an owning buffer type must also override the API < 27 parcel pipe path,
  which streams from a background thread after `writeToParcel` returns.

Related: `~/git/ANDROID-NONMOVABLE-OOM-HANDOFF.md` (original handoff; its API-34
claim was right, but it predates the LOS-fallback mechanism and image-flavor
findings recorded here).
