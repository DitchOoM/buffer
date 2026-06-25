/**
 * Loader for the real compiled `buffer-crypto` AES-GCM facade.
 *
 * `static/crypto/buffer-crypto-kt.js` is the webpack bundle of the module's JS target (see that
 * folder's README to regenerate). It exposes the `@JsExport` `CryptoDemo` object — the same witness
 * ops the library ships — as a `bufferCryptoKt` global. So the widget drives the actual Kotlin
 * code path (`CryptoDemo.kt`), not a JS re-implementation.
 */

/** Mirrors the `@JsExport` surface of `com.ditchoom.buffer.crypto.CryptoDemo`. */
export interface CryptoFacade {
  generateKeyHex(): string;
  generateNonceHex(): string;
  seal(keyHex: string, plaintext: string, aad: string): Promise<string>;
  /** Demo-only: seal with a pinned nonce so editing the plaintext shows the ciphertext tracking it. */
  sealWithNonce(keyHex: string, nonceHex: string, plaintext: string, aad: string): Promise<string>;
  open(keyHex: string, sealedHex: string, aad: string): Promise<string>;
  capabilities(): string;
}

/** First 12 bytes of the sealed buffer are the nonce; trailing 16 are the GCM tag. */
export const NONCE_HEX = 12 * 2;
export const TAG_HEX = 16 * 2;

let cached: Promise<CryptoFacade> | null = null;

function pick(): CryptoFacade {
  const g = globalThis as unknown as {
    bufferCryptoKt?: { com: { ditchoom: { buffer: { crypto: { CryptoDemo: CryptoFacade } } } } };
  };
  const demo = g.bufferCryptoKt?.com?.ditchoom?.buffer?.crypto?.CryptoDemo;
  if (!demo) throw new Error('buffer-crypto bundle loaded but CryptoDemo was not found');
  return demo;
}

/** Lazily injects the bundle (once) and resolves the compiled `CryptoDemo` facade. */
export function loadCryptoFacade(scriptUrl: string): Promise<CryptoFacade> {
  if (cached) return cached;
  cached = new Promise<CryptoFacade>((resolve, reject) => {
    if ((globalThis as { bufferCryptoKt?: unknown }).bufferCryptoKt) {
      resolve(pick());
      return;
    }
    const script = document.createElement('script');
    script.src = scriptUrl;
    script.async = true;
    script.onload = () => {
      try {
        resolve(pick());
      } catch (e) {
        reject(e);
      }
    };
    script.onerror = () => reject(new Error(`failed to load ${scriptUrl}`));
    document.head.appendChild(script);
  });
  return cached;
}
