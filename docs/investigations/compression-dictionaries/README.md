# Investigation: preset dictionaries for `buffer-compression`

Tracking issue: [DitchOoM/buffer#270](https://github.com/DitchOoM/buffer/issues/270)

## Question

Two related questions:

1. Should `buffer-compression` support **preset dictionaries** to improve compression of
   many small, structurally-similar messages (MQTT, WebSocket frames, telemetry, RPC)?
2. Could **`buffer-codec`** auto-generate that dictionary from the schema knowledge it has
   at compile time (discriminator bytes, framing, enum IDs)?

## TL;DR

- **Yes** to (1) — a small preset dictionary is a *free lunch*: smaller wire **and** faster.
- **No** to (2) — a codec/schema-derived dictionary is near-worthless (0–6%); all the real
  gain comes from payload *values* the codec fundamentally cannot see.

## Method

Throwaway JVM spikes using `java.util.zip.Deflater/Inflater` directly. This is faithful to
`buffer-compression` because its JVM path (`CompressionAlgorithm.Raw`) delegates to exactly
this (raw deflate, `nowrap`). Messages are compressed **individually** (the small-message
scenario where dictionaries matter). All round-trips are verified before timing.

Corpus: realistic MQTT-telemetry traffic (PUBLISH-dominant, plus SUBSCRIBE / CONNECT /
PINGREQ / PUBACK), ~69 B average, seeded/deterministic.

Dictionary strategies compared:

| Strategy | Contents | Represents |
|---|---|---|
| `NONE` | — | baseline |
| `STRUCTURAL` | only codec-known bytes: packet-type discriminators, v5 property IDs, framing | the ceiling of a codec-generated dictionary |
| `HYBRID` | small (~220 B): structural + value patterns (`"MQTT"`, topic strings, JSON keys) | codec-seeded + runtime-trained |
| `ORACLE` | 32 KB corpus-trained on a held-out split | full runtime training |

## Results

### Wire savings (per-message compression ratio)

| Strategy | avg compressed | bytes saved/msg | wire ratio | vs NONE |
|---|---|---|---|---|
| NONE | 66.0 B | +3.5 B | 0.95 | 0% |
| STRUCTURAL | 66.0 B | +3.5 B | 0.95 | **0.0%** |
| HYBRID (220 B) | 36.4 B | +33 B | 0.52 | **44.8%** |
| ORACLE (32 KB) | 19.0 B | +50 B | 0.27 | **71.2%** |

Best case for `STRUCTURAL` — a protocol dispatched on a *multi-byte* known magic tag
(`DictSpike2`) — still only reached **5.9%**, and left most messages expanding.

### Runtime cost (reused `Deflater`/`Inflater`, `reset()` + `setDictionary()` per message)

| Strategy | compress | decompress |
|---|---|---|
| NONE | 3787 ns/msg | 421 ns/msg |
| HYBRID (220 B) | **3428 ns/msg** (faster) | **276 ns/msg** (faster) |
| ORACLE (32 KB) | **28664 ns/msg (8×)** | 724 ns/msg |

## Findings

1. **A small tuned dictionary is a free lunch** — ~48% smaller wire *and* faster
   encode/decode than no compression (smaller output = less work; tiny-dict setup is
   negligible). Strictly dominant.
2. **A large (KB-scale) dictionary buys more (~73%) but costs 8× compress CPU** — and the
   cost is `setDictionary(32 KB)` re-armed every message after `reset()`, not the
   compression. `java.util.zip` can't keep a dictionary "digested" across messages;
   **zstd's prepared `CDict`/`DDict` solve exactly this.**
3. **The codec cannot generate a useful dictionary.** DEFLATE only emits back-references for
   matches ≥3 bytes; the codec knows 1–2 byte discriminators/enum IDs, below that floor.
   All the real gain is in payload *values* the codec cannot see (`buffer-codec` generates
   codecs for data classes — no compile-time field values or string literals).

## Outcome

The high-ROI recommendations from this investigation **shipped** in
[#272](https://github.com/DitchOoM/buffer/pull/272):

1. ✅ **`setDictionary` seam** — `compress`/`decompress` and the streaming `create()`
   factories now take a `dictionary: ReadBuffer? = null`, and `CompressionAlgorithm`
   exposes `supportsDictionary()`. The old `needsDictionary()` → throw stub is gone; the
   dictionary is actually applied. (See `DictionaryTests.kt`, which cites this
   investigation's "small dictionary is a free lunch" result.)
2. ✅ Dictionaries are fed at runtime by the consumer (corpus/runtime-trained),
   **independent of `buffer-codec`** — no schema-derived content.

Still open / not pursued:

3. "Go big" follow-on: zstd support with digested dictionaries (avoids the per-message
   `setDictionary` tax). Not yet implemented.
4. Optional: codec exposes field-boundary *hints* to help a runtime trainer align samples —
   but dictionary *content* must stay value-derived. Not implemented.
5. **Rejected:** codec-generated (schema-derived) dictionaries. 0–6%, not worth the
   schema-hash/versioning machinery, and a silent-corruption footgun on schema skew.

## Reproduce

```bash
cd docs/investigations/compression-dictionaries
javac DictSpike.java  && java DictSpike    # scenario 1: MQTT, 4-strategy bucket table
javac DictSpike2.java && java DictSpike2   # scenario 2: strongest case for STRUCTURAL (magic tags)
javac DictSpike3.java && java DictSpike3   # runtime cost/benefit: ratio + throughput
```

Numbers are from a synthetic-but-realistic corpus; the mechanism (deflate's 3-byte match
floor, low entropy of structural bytes, per-message `setDictionary` cost) is protocol- and
codec-independent, so the direction holds. Re-confirm ratios on real captured traffic when
implementing.

> These `.java` files are **throwaway measurement harnesses**, not library code — they use
> `java.util.zip` (which mandates `byte[]`) and live under `docs/` outside any Gradle source
> set, so they are never compiled into the build.
