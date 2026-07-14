// C surface for the CryptoKit Swift shim (CryptoKitShim.swift).
//
// CryptoKit has no Objective-C / C interface, so the Swift shim re-exposes the primitives we need
// through `@_cdecl` plain-C functions. This header declares exactly those functions so Kotlin/Native
// cinterop can bind them (see cryptokitshim.def). The symbols resolve from the static archive built
// from CryptoKitShim.swift and linked into the Apple test/binary by the cinterop linkerOpts.
//
// All byte buffers are (pointer, length); outputs are (pointer, capacity) plus a written-length
// out-parameter. Every function returns one of the status codes below; no secret or error text is
// ever returned through the status.

#ifndef BUFFER_CRYPTO_CRYPTOKIT_SHIM_H
#define BUFFER_CRYPTO_CRYPTOKIT_SHIM_H

#include <stdint.h>
#include <stddef.h>

#define BCKS_OK 0
#define BCKS_ERR_INPUT (-1)
#define BCKS_ERR_AUTH (-2)
#define BCKS_ERR_BUFFER (-3)
#define BCKS_ERR_INTERNAL (-4)

// ChaCha20-Poly1305.
int32_t bcks_chachapoly_seal(
    const uint8_t *keyPtr, size_t keyLen,
    const uint8_t *noncePtr, size_t nonceLen,
    const uint8_t *aadPtr, size_t aadLen,
    const uint8_t *ptPtr, size_t ptLen,
    uint8_t *ctOut, size_t ctCap,
    uint8_t *tagOut, size_t tagCap);

int32_t bcks_chachapoly_open(
    const uint8_t *keyPtr, size_t keyLen,
    const uint8_t *noncePtr, size_t nonceLen,
    const uint8_t *aadPtr, size_t aadLen,
    const uint8_t *ctPtr, size_t ctLen,
    const uint8_t *tagPtr, size_t tagLen,
    uint8_t *ptOut, size_t ptCap,
    size_t *ptLenOut);

// Ed25519.
int32_t bcks_ed25519_public_key(
    const uint8_t *seedPtr, size_t seedLen,
    uint8_t *pubOut, size_t pubCap,
    size_t *pubLenOut);

int32_t bcks_ed25519_sign(
    const uint8_t *seedPtr, size_t seedLen,
    const uint8_t *msgPtr, size_t msgLen,
    uint8_t *sigOut, size_t sigCap,
    size_t *sigLenOut);

int32_t bcks_ed25519_verify(
    const uint8_t *pubPtr, size_t pubLen,
    const uint8_t *msgPtr, size_t msgLen,
    const uint8_t *sigPtr, size_t sigLen);

// X25519.
int32_t bcks_x25519_generate(
    uint8_t *privOut, size_t privCap, size_t *privLenOut,
    uint8_t *pubOut, size_t pubCap, size_t *pubLenOut);

int32_t bcks_x25519_public_key(
    const uint8_t *privPtr, size_t privLen,
    uint8_t *pubOut, size_t pubCap,
    size_t *pubLenOut);

int32_t bcks_x25519_agree(
    const uint8_t *privPtr, size_t privLen,
    const uint8_t *peerPtr, size_t peerLen,
    uint8_t *secretOut, size_t secretCap,
    size_t *secretLenOut);

// ECDH: reconstruct the ANSI X9.63 private representation (04 ‖ X ‖ Y ‖ K) from a bare scalar
// (curve: 256, 384, or 521). Lets the Security-framework agreement path accept a stored raw scalar,
// so the key-agreement private encoding is the same raw big-endian scalar on every platform.
int32_t bcks_ecdh_x963_from_scalar(
    int32_t curve,
    const uint8_t *scalarPtr, size_t scalarLen,
    uint8_t *x963Out, size_t x963Cap,
    size_t *x963LenOut);

// EC point decompression (curve: 256, 384, or 521): SEC1 compressed (0x02/0x03 || X) -> uncompressed
// (0x04 || X || Y) via CryptoKit. Returns BCKS_ERR_INPUT for an off-curve point and BCKS_ERR_INTERNAL
// when the running OS predates CryptoKit's compressed-point support (macOS 13 / iOS 16 / watchOS 9 /
// tvOS 16); the Kotlin actual maps the latter to UnsupportedOperationException.
int32_t bcks_ec_decompress(
    int32_t curve,
    const uint8_t *pointPtr, size_t pointLen,
    uint8_t *out, size_t outCap,
    size_t *outLenOut);

// ECDSA signing from a bare scalar (curve: 256, 384, or 521).
int32_t bcks_ecdsa_sign_from_scalar(
    int32_t curve,
    const uint8_t *scalarPtr, size_t scalarLen,
    const uint8_t *msgPtr, size_t msgLen,
    uint8_t *sigOut, size_t sigCap,
    size_t *sigLenOut);

// Secure Enclave (hardware-backed P-256 signing).
int32_t bcks_secure_enclave_available(void);

int32_t bcks_secure_enclave_p256_generate(
    uint8_t *blobOut, size_t blobCap, size_t *blobLenOut,
    uint8_t *pointOut, size_t pointCap, size_t *pointLenOut);

int32_t bcks_secure_enclave_p256_sign(
    const uint8_t *blobPtr, size_t blobLen,
    const uint8_t *msgPtr, size_t msgLen,
    uint8_t *sigOut, size_t sigCap,
    size_t *sigLenOut);

// User-authentication binding (LocalAuthentication + SecAccessControl). LocalAuthentication does
// not exist on every Apple platform (no tvOS): bcks_la_context_create returns 0 there and the
// Kotlin side surfaces a typed unsupported/authenticator-required error instead.

// Creates a LocalAuthentication context carrying the user-facing prompt reason (UTF-8).
// Returns an opaque handle (> 0), or 0 when LocalAuthentication is unavailable.
int64_t bcks_la_context_create(const char *reasonUtf8);

// Invalidates and releases a context created by bcks_la_context_create. Idempotent.
void bcks_la_context_release(int64_t handle);

// Evaluates the context's auth policy, prompting the user (method: 1 = biometric or device
// credential, 2 = biometric only). interactionAllowed == 0 fails instead of prompting. BLOCKING —
// call off the main thread. BCKS_OK on success, BCKS_ERR_AUTH on denial.
int32_t bcks_la_evaluate(int64_t handle, int32_t method, int32_t interactionAllowed);

// Generates a Secure Enclave P-256 signing key bound to user authentication via SecAccessControl
// (authReq: 1 = userPresence, 2 = biometryCurrentSet). Outputs match
// bcks_secure_enclave_p256_generate.
int32_t bcks_secure_enclave_p256_generate_ac(
    int32_t authReq,
    uint8_t *blobOut, size_t blobCap, size_t *blobLenOut,
    uint8_t *pointOut, size_t pointCap, size_t *pointLenOut);

// Signs with the Enclave key reconstructed from its blob, authorized through the LAContext behind
// laHandle (0 = no context). BCKS_ERR_AUTH when user authentication was denied.
int32_t bcks_secure_enclave_p256_sign_ctx(
    const uint8_t *blobPtr, size_t blobLen,
    int64_t laHandle,
    const uint8_t *msgPtr, size_t msgLen,
    uint8_t *sigOut, size_t sigCap,
    size_t *sigLenOut);

// Keychain persistence — durable generic-password items for the alias-addressable key store.
// `service` and `account` are NUL-terminated UTF-8 C strings (account == the caller's alias). The
// stored value is opaque store-owned bytes (the framed public point + Enclave restore blob) — never
// a private key: the Secure Enclave holds the private scalar, and the blob is an encrypted
// representation only that same Enclave can restore. Items are ThisDeviceOnly + WhenUnlocked, so
// they never sync off-device and are unreadable while the device is locked.

// Stores dataPtr/dataLen under (service, account), replacing any existing item. BCKS_OK, or
// BCKS_ERR_INTERNAL on a keychain failure.
int32_t bcks_keychain_put(
    const char *service, const char *account,
    const uint8_t *dataPtr, size_t dataLen);

// Reads the item under (service, account) into out/outCap, writing its length to outLenOut. BCKS_OK
// when found, BCKS_ERR_INPUT when no such item exists, BCKS_ERR_BUFFER when out is too small.
int32_t bcks_keychain_get(
    const char *service, const char *account,
    uint8_t *out, size_t outCap, size_t *outLenOut);

// 1 if an item exists under (service, account), 0 if not, negative on a keychain failure.
int32_t bcks_keychain_contains(const char *service, const char *account);

// Deletes the item under (service, account). BCKS_OK if one was removed, BCKS_ERR_INPUT if none
// existed, negative on a keychain failure.
int32_t bcks_keychain_delete(const char *service, const char *account);

// Writes every account name under `service`, newline-separated UTF-8 (accounts are validated to a
// charset without '\n'), into out/outCap with the byte count in outLenOut. BCKS_OK (0 length when
// none), BCKS_ERR_BUFFER when out is too small.
int32_t bcks_keychain_aliases(
    const char *service,
    uint8_t *out, size_t outCap, size_t *outLenOut);

#endif // BUFFER_CRYPTO_CRYPTOKIT_SHIM_H
