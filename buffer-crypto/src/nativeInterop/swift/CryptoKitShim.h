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

#endif // BUFFER_CRYPTO_CRYPTOKIT_SHIM_H
