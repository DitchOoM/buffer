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

// ECDSA signing from a bare scalar (curve: 256, 384, or 521).
int32_t bcks_ecdsa_sign_from_scalar(
    int32_t curve,
    const uint8_t *scalarPtr, size_t scalarLen,
    const uint8_t *msgPtr, size_t msgLen,
    uint8_t *sigOut, size_t sigCap,
    size_t *sigLenOut);

#endif // BUFFER_CRYPTO_CRYPTOKIT_SHIM_H
