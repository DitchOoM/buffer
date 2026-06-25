# `buffer-crypto-kt.js` — vendored demo bundle

`buffer-crypto-kt.js` is the self-contained webpack bundle of the `buffer-crypto` JS target,
exposing the `@JsExport` `CryptoDemo` facade as a `bufferCryptoKt` global. The interactive
Cryptography recipe loads it to run the **real** library AES-GCM path in the reader's browser.

It is a build artifact, committed here so the docs site has no Gradle dependency at build time.

## Regenerate after changing `CryptoDemo.kt`

```bash
./gradlew :buffer-crypto:jsBrowserProductionWebpack
cp buffer-crypto/build/kotlin-webpack/js/productionExecutable/buffer-crypto.js \
   docs/static/crypto/buffer-crypto-kt.js
```

Access path inside the bundle: `bufferCryptoKt.com.ditchoom.buffer.crypto.CryptoDemo`.
