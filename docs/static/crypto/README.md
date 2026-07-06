# `buffer-crypto-kt.js` — vendored demo bundle

`buffer-crypto-kt.js` is the self-contained webpack bundle of the `buffer-crypto` JS target,
exposing the `@JsExport` `CryptoDemo` (AES-GCM) and `CryptoAsymDemo` (key agreement, HPKE,
signatures) facades as a `bufferCryptoKt` global. The interactive Cryptography recipe pages load it
to run the **real** library crypto paths in the reader's browser.

It is a build artifact, committed here so the docs site has no Gradle dependency at build time.

## Regenerate after changing `CryptoDemo.kt` or `CryptoAsymDemo.kt`

```bash
./gradlew :buffer-crypto:jsBrowserProductionWebpack
cp buffer-crypto/build/kotlin-webpack/js/productionExecutable/buffer-crypto.js \
   docs/static/crypto/buffer-crypto-kt.js
```

Access paths inside the bundle:
- `bufferCryptoKt.com.ditchoom.buffer.crypto.CryptoDemo` (AES-GCM)
- `bufferCryptoKt.com.ditchoom.buffer.crypto.CryptoAsymDemo` (key agreement, HPKE, signatures)
