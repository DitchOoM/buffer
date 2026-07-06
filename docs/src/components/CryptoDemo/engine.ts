/**
 * Loader for the real compiled `buffer-crypto` facades.
 *
 * `static/crypto/buffer-crypto-kt.js` is the webpack bundle of the module's JS target (see that
 * folder's README to regenerate). It exposes two `@JsExport` objects on the `bufferCryptoKt`
 * global: `CryptoDemo` (AES-GCM) and `CryptoAsymDemo` (key agreement, HPKE, signatures) — the same
 * witness ops the library ships, split into two facades purely so each stays under the Kotlin side's
 * complexity gate. So the widgets drive the actual Kotlin code paths (`CryptoDemo.kt` /
 * `CryptoAsymDemo.kt`), not a JS re-implementation.
 */

/** Mirrors the `@JsExport` surface of `com.ditchoom.buffer.crypto.CryptoDemo` (AES-GCM). */
export interface CryptoFacade {
  generateKeyHex(): string;
  generateNonceHex(): string;
  seal(keyHex: string, plaintext: string, aad: string): Promise<string>;
  /** Demo-only: seal with a pinned nonce so editing the plaintext shows the ciphertext tracking it. */
  sealWithNonce(keyHex: string, nonceHex: string, plaintext: string, aad: string): Promise<string>;
  open(keyHex: string, sealedHex: string, aad: string): Promise<string>;
  capabilities(): string;
}

/**
 * Mirrors the `@JsExport` surface of `com.ditchoom.buffer.crypto.CryptoAsymDemo` (key agreement,
 * HPKE, signatures; real WebCrypto bytes, ':'-delimited hex results split by the callers).
 */
export interface CryptoAsymFacade {
  /** X25519 exchange → `pkAlice:pkBob:sharedFromAlice:sharedFromBob` (the two shared values match). */
  x25519Exchange(): Promise<string>;
  /** HPKE seal to a fresh recipient → `pkBob:enc:ciphertext:recoveredHex:wrongKeyRejected(0|1)`. */
  hpkeSealToRecipient(plaintext: string): Promise<string>;
  /** Ed25519 keypair → `seedHex:publicKeyHex` (both round-trip through sign/verify). */
  ed25519GenerateKeypair(): Promise<string>;
  /** Ed25519 signature of UTF-8 `message` under `seedHex`(+`publicKeyHex`) → signature hex. */
  ed25519Sign(seedHex: string, publicKeyHex: string, message: string): Promise<string>;
  /** Ed25519 verify → true only if `signatureHex` matches `message` under `publicKeyHex`. */
  ed25519Verify(publicKeyHex: string, message: string, signatureHex: string): Promise<boolean>;
}

/** First 12 bytes of the sealed buffer are the nonce; trailing 16 are the GCM tag. */
export const NONCE_HEX = 12 * 2;
export const TAG_HEX = 16 * 2;

interface BufferCryptoBundle {
  com: { ditchoom: { buffer: { crypto: { CryptoDemo: CryptoFacade; CryptoAsymDemo: CryptoAsymFacade } } } };
}

let bundleLoad: Promise<BufferCryptoBundle> | null = null;

/** Lazily injects the webpack bundle (once, shared by both facades) and resolves the global. */
function loadBundle(scriptUrl: string): Promise<BufferCryptoBundle> {
  if (bundleLoad) return bundleLoad;
  bundleLoad = new Promise<BufferCryptoBundle>((resolve, reject) => {
    const existing = (globalThis as { bufferCryptoKt?: BufferCryptoBundle }).bufferCryptoKt;
    if (existing) {
      resolve(existing);
      return;
    }
    const script = document.createElement('script');
    script.src = scriptUrl;
    script.async = true;
    script.onload = () => {
      const loaded = (globalThis as { bufferCryptoKt?: BufferCryptoBundle }).bufferCryptoKt;
      if (loaded) resolve(loaded);
      else reject(new Error('buffer-crypto bundle loaded but bufferCryptoKt was not found'));
    };
    script.onerror = () => reject(new Error(`failed to load ${scriptUrl}`));
    document.head.appendChild(script);
  });
  return bundleLoad;
}

/** Lazily injects the bundle and resolves the compiled `CryptoDemo` (AES-GCM) facade. */
export function loadCryptoFacade(scriptUrl: string): Promise<CryptoFacade> {
  return loadBundle(scriptUrl).then((bundle) => {
    const demo = bundle.com?.ditchoom?.buffer?.crypto?.CryptoDemo;
    if (!demo) throw new Error('buffer-crypto bundle loaded but CryptoDemo was not found');
    return demo;
  });
}

/** Lazily injects the bundle and resolves the compiled `CryptoAsymDemo` (asymmetric ops) facade. */
export function loadCryptoAsymFacade(scriptUrl: string): Promise<CryptoAsymFacade> {
  return loadBundle(scriptUrl).then((bundle) => {
    const demo = bundle.com?.ditchoom?.buffer?.crypto?.CryptoAsymDemo;
    if (!demo) throw new Error('buffer-crypto bundle loaded but CryptoAsymDemo was not found');
    return demo;
  });
}
