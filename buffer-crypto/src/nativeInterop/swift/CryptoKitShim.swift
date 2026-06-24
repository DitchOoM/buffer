// CryptoKit shim for buffer-crypto Apple actuals.
//
// CryptoKit is Swift-only and exposes no Objective-C / C interface, so Kotlin/Native cinterop
// cannot bind it directly the way it binds CommonCrypto / Security. This file re-exposes the four
// CryptoKit primitives the module needs (ChaCha20-Poly1305 AEAD, Ed25519 signatures, X25519 key
// agreement, ECDSA signing from a bare scalar) through a flat `@_cdecl` plain-C surface that
// cinterop CAN bind (see CryptoKitShim.h / cryptokitshim.def).
//
// ABI conventions:
//  - Byte buffers are passed as (pointer, length) pairs; output buffers as (pointer, capacity) plus
//    an out-parameter that receives the number of bytes actually written.
//  - Every function returns an Int32 status: BCKS_OK (0) on success, a negative code on failure.
//    No error string or secret material is ever returned through the status.
//  - Secrets are handled inside the Swift heap and released by ARC at function exit; nothing is
//    logged. The Kotlin side keeps key material in its own wiped SecureBuffer and only passes
//    pointers in for the duration of the call.

import CryptoKit
import Foundation

// Status codes — kept in sync with CryptoKitShim.h.
private let BCKS_OK: Int32 = 0
private let BCKS_ERR_INPUT: Int32 = -1 // malformed key / nonce / point / signature
private let BCKS_ERR_AUTH: Int32 = -2 // AEAD tag mismatch / signature did not verify
private let BCKS_ERR_BUFFER: Int32 = -3 // output buffer too small
private let BCKS_ERR_INTERNAL: Int32 = -4 // unexpected CryptoKit failure

// Copy a CryptoKit Data result into a caller-provided output buffer, writing the length out.
private func emit(_ data: Data, _ out: UnsafeMutablePointer<UInt8>?, _ outCap: Int, _ outLen: UnsafeMutablePointer<Int>?) -> Int32 {
    if data.count > outCap { return BCKS_ERR_BUFFER }
    if let out = out, data.count > 0 {
        data.copyBytes(to: out, count: data.count)
    }
    outLen?.pointee = data.count
    return BCKS_OK
}

// Build a Data view over a (pointer, length) pair without copying (valid only for the call).
private func bytes(_ ptr: UnsafePointer<UInt8>?, _ len: Int) -> Data {
    guard let ptr = ptr, len > 0 else { return Data() }
    return Data(bytes: ptr, count: len)
}

// =============================================================================
// ChaCha20-Poly1305 (AEAD) — CryptoKit ChaChaPoly
// =============================================================================

// Seal: writes ciphertext (== plaintext length) into ctOut and the 16-byte tag into tagOut.
@_cdecl("bcks_chachapoly_seal")
public func bcks_chachapoly_seal(
    _ keyPtr: UnsafePointer<UInt8>?, _ keyLen: Int,
    _ noncePtr: UnsafePointer<UInt8>?, _ nonceLen: Int,
    _ aadPtr: UnsafePointer<UInt8>?, _ aadLen: Int,
    _ ptPtr: UnsafePointer<UInt8>?, _ ptLen: Int,
    _ ctOut: UnsafeMutablePointer<UInt8>?, _ ctCap: Int,
    _ tagOut: UnsafeMutablePointer<UInt8>?, _ tagCap: Int
) -> Int32 {
    let key = SymmetricKey(data: bytes(keyPtr, keyLen))
    guard let nonce = try? ChaChaPoly.Nonce(data: bytes(noncePtr, nonceLen)) else { return BCKS_ERR_INPUT }
    let aad = bytes(aadPtr, aadLen)
    let pt = bytes(ptPtr, ptLen)
    guard let box = try? ChaChaPoly.seal(pt, using: key, nonce: nonce, authenticating: aad) else {
        return BCKS_ERR_INTERNAL
    }
    if box.ciphertext.count > ctCap || box.tag.count > tagCap { return BCKS_ERR_BUFFER }
    if let ctOut = ctOut, box.ciphertext.count > 0 { box.ciphertext.copyBytes(to: ctOut, count: box.ciphertext.count) }
    if let tagOut = tagOut { box.tag.copyBytes(to: tagOut, count: box.tag.count) }
    return BCKS_OK
}

// Open: authenticates and decrypts; writes plaintext (== ciphertext length) into ptOut.
// Returns BCKS_ERR_AUTH on tag mismatch without touching ptOut.
@_cdecl("bcks_chachapoly_open")
public func bcks_chachapoly_open(
    _ keyPtr: UnsafePointer<UInt8>?, _ keyLen: Int,
    _ noncePtr: UnsafePointer<UInt8>?, _ nonceLen: Int,
    _ aadPtr: UnsafePointer<UInt8>?, _ aadLen: Int,
    _ ctPtr: UnsafePointer<UInt8>?, _ ctLen: Int,
    _ tagPtr: UnsafePointer<UInt8>?, _ tagLen: Int,
    _ ptOut: UnsafeMutablePointer<UInt8>?, _ ptCap: Int,
    _ ptLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    let key = SymmetricKey(data: bytes(keyPtr, keyLen))
    guard let nonce = try? ChaChaPoly.Nonce(data: bytes(noncePtr, nonceLen)) else { return BCKS_ERR_INPUT }
    let aad = bytes(aadPtr, aadLen)
    guard let box = try? ChaChaPoly.SealedBox(
        nonce: nonce,
        ciphertext: bytes(ctPtr, ctLen),
        tag: bytes(tagPtr, tagLen)
    ) else { return BCKS_ERR_INPUT }
    guard let pt = try? ChaChaPoly.open(box, using: key, authenticating: aad) else {
        return BCKS_ERR_AUTH
    }
    return emit(pt, ptOut, ptCap, ptLenOut)
}

// =============================================================================
// Ed25519 (signatures) — CryptoKit Curve25519.Signing
// =============================================================================

// Derive the 32-byte public key from a 32-byte private seed.
@_cdecl("bcks_ed25519_public_key")
public func bcks_ed25519_public_key(
    _ seedPtr: UnsafePointer<UInt8>?, _ seedLen: Int,
    _ pubOut: UnsafeMutablePointer<UInt8>?, _ pubCap: Int,
    _ pubLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: bytes(seedPtr, seedLen)) else {
        return BCKS_ERR_INPUT
    }
    return emit(priv.publicKey.rawRepresentation, pubOut, pubCap, pubLenOut)
}

// Sign message under a 32-byte private seed; writes the 64-byte signature into sigOut.
@_cdecl("bcks_ed25519_sign")
public func bcks_ed25519_sign(
    _ seedPtr: UnsafePointer<UInt8>?, _ seedLen: Int,
    _ msgPtr: UnsafePointer<UInt8>?, _ msgLen: Int,
    _ sigOut: UnsafeMutablePointer<UInt8>?, _ sigCap: Int,
    _ sigLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: bytes(seedPtr, seedLen)) else {
        return BCKS_ERR_INPUT
    }
    guard let sig = try? priv.signature(for: bytes(msgPtr, msgLen)) else { return BCKS_ERR_INTERNAL }
    return emit(sig, sigOut, sigCap, sigLenOut)
}

// Verify a 64-byte signature over message under a 32-byte public key.
// Returns BCKS_OK if valid, BCKS_ERR_AUTH if not, BCKS_ERR_INPUT if the key is malformed.
@_cdecl("bcks_ed25519_verify")
public func bcks_ed25519_verify(
    _ pubPtr: UnsafePointer<UInt8>?, _ pubLen: Int,
    _ msgPtr: UnsafePointer<UInt8>?, _ msgLen: Int,
    _ sigPtr: UnsafePointer<UInt8>?, _ sigLen: Int
) -> Int32 {
    guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: bytes(pubPtr, pubLen)) else {
        return BCKS_ERR_INPUT
    }
    let ok = pub.isValidSignature(bytes(sigPtr, sigLen), for: bytes(msgPtr, msgLen))
    return ok ? BCKS_OK : BCKS_ERR_AUTH
}

// =============================================================================
// X25519 (key agreement) — CryptoKit Curve25519.KeyAgreement
// =============================================================================

// Generate an X25519 key pair: 32-byte private scalar into privOut, 32-byte public key into pubOut.
@_cdecl("bcks_x25519_generate")
public func bcks_x25519_generate(
    _ privOut: UnsafeMutablePointer<UInt8>?, _ privCap: Int,
    _ privLenOut: UnsafeMutablePointer<Int>?,
    _ pubOut: UnsafeMutablePointer<UInt8>?, _ pubCap: Int,
    _ pubLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    let priv = Curve25519.KeyAgreement.PrivateKey()
    let s = emit(priv.rawRepresentation, privOut, privCap, privLenOut)
    if s != BCKS_OK { return s }
    return emit(priv.publicKey.rawRepresentation, pubOut, pubCap, pubLenOut)
}

// Derive the 32-byte public key from a 32-byte private scalar.
@_cdecl("bcks_x25519_public_key")
public func bcks_x25519_public_key(
    _ privPtr: UnsafePointer<UInt8>?, _ privLen: Int,
    _ pubOut: UnsafeMutablePointer<UInt8>?, _ pubCap: Int,
    _ pubLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: bytes(privPtr, privLen)) else {
        return BCKS_ERR_INPUT
    }
    return emit(priv.publicKey.rawRepresentation, pubOut, pubCap, pubLenOut)
}

// Compute the raw 32-byte X25519 shared secret. Caller enforces the RFC 7748 all-zero rejection.
@_cdecl("bcks_x25519_agree")
public func bcks_x25519_agree(
    _ privPtr: UnsafePointer<UInt8>?, _ privLen: Int,
    _ peerPtr: UnsafePointer<UInt8>?, _ peerLen: Int,
    _ secretOut: UnsafeMutablePointer<UInt8>?, _ secretCap: Int,
    _ secretLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: bytes(privPtr, privLen)) else {
        return BCKS_ERR_INPUT
    }
    guard let peer = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: bytes(peerPtr, peerLen)) else {
        return BCKS_ERR_INPUT
    }
    guard let shared = try? priv.sharedSecretFromKeyAgreement(with: peer) else {
        return BCKS_ERR_INPUT
    }
    let raw = shared.withUnsafeBytes { Data($0) }
    return emit(raw, secretOut, secretCap, secretLenOut)
}

// =============================================================================
// ECDSA signing from a bare scalar — CryptoKit P256/P384/P521.Signing
// =============================================================================
//
// Security.framework's SecKeyCreateWithData needs the full X9.63 private representation
// (04 ‖ X ‖ Y ‖ K); it cannot derive the public point from the scalar. CryptoKit's
// P###.Signing.PrivateKey(rawRepresentation:) accepts the bare big-endian scalar and derives the
// point internally, then signs (hash-then-sign with the curve's paired SHA-2) and emits DER.

private func ecdsaSign(_ curve: Int32, _ scalar: Data, _ msg: Data) -> Data? {
    switch curve {
    case 256:
        guard let k = try? P256.Signing.PrivateKey(rawRepresentation: scalar) else { return nil }
        return (try? k.signature(for: msg))?.derRepresentation
    case 384:
        guard let k = try? P384.Signing.PrivateKey(rawRepresentation: scalar) else { return nil }
        return (try? k.signature(for: msg))?.derRepresentation
    case 521:
        guard let k = try? P521.Signing.PrivateKey(rawRepresentation: scalar) else { return nil }
        return (try? k.signature(for: msg))?.derRepresentation
    default:
        return nil
    }
}

// Sign message with an ECDSA private scalar for the given curve (256/384/521), emitting a DER
// signature. The hash is the curve's paired SHA-2 (P-256↔SHA-256, P-384↔SHA-384, P-521↔SHA-512),
// matching the Security.framework ECDSA path and the cross-platform DER contract.
@_cdecl("bcks_ecdsa_sign_from_scalar")
public func bcks_ecdsa_sign_from_scalar(
    _ curve: Int32,
    _ scalarPtr: UnsafePointer<UInt8>?, _ scalarLen: Int,
    _ msgPtr: UnsafePointer<UInt8>?, _ msgLen: Int,
    _ sigOut: UnsafeMutablePointer<UInt8>?, _ sigCap: Int,
    _ sigLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let sig = ecdsaSign(curve, bytes(scalarPtr, scalarLen), bytes(msgPtr, msgLen)) else {
        return BCKS_ERR_INPUT
    }
    return emit(sig, sigOut, sigCap, sigLenOut)
}

// =============================================================================
// Secure Enclave (hardware-backed P-256 signing) — CryptoKit SecureEnclave.P256.Signing
// =============================================================================
//
// The Secure Enclave generates and holds the P-256 private key; the key never leaves the element.
// `dataRepresentation` is an *encrypted* blob that only the same Enclave can restore — it is NOT the
// private key and is safe to persist / pass around. Signing reconstructs the key handle from that
// blob and signs inside the Enclave, emitting DER (matching the cross-platform ECDSA contract).
//
// AES-GCM is deliberately NOT offered: CryptoKit exposes no symmetric Secure Enclave key, and the
// only "Enclave-tied" AES one could build (ECDH a P-256 Enclave key, derive a symmetric key, run
// AES.GCM in software) would put the AES key in process memory — violating the non-exportable
// hardware-key contract. So the Enclave provider backs signatures only.

// 1 if a Secure Enclave is present on this device, else 0. (Presence only — actual usability also
// depends on code-signing entitlements, which the Kotlin side probes by attempting a generation.)
@_cdecl("bcks_secure_enclave_available")
public func bcks_secure_enclave_available() -> Int32 {
    return SecureEnclave.isAvailable ? 1 : 0
}

// Generate a P-256 signing key inside the Secure Enclave. Writes the persistent encrypted key blob
// into blobOut and the uncompressed SEC1 public point (04 ‖ X ‖ Y) into pointOut.
@_cdecl("bcks_secure_enclave_p256_generate")
public func bcks_secure_enclave_p256_generate(
    _ blobOut: UnsafeMutablePointer<UInt8>?, _ blobCap: Int,
    _ blobLenOut: UnsafeMutablePointer<Int>?,
    _ pointOut: UnsafeMutablePointer<UInt8>?, _ pointCap: Int,
    _ pointLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard SecureEnclave.isAvailable else { return BCKS_ERR_INTERNAL }
    guard let key = try? SecureEnclave.P256.Signing.PrivateKey() else { return BCKS_ERR_INTERNAL }
    let s = emit(key.dataRepresentation, blobOut, blobCap, blobLenOut)
    if s != BCKS_OK { return s }
    return emit(key.publicKey.x963Representation, pointOut, pointCap, pointLenOut)
}

// Sign message with the Enclave key reconstructed from its blob; emits a DER signature. Returns
// BCKS_ERR_INPUT if the blob is not restorable by this Enclave.
@_cdecl("bcks_secure_enclave_p256_sign")
public func bcks_secure_enclave_p256_sign(
    _ blobPtr: UnsafePointer<UInt8>?, _ blobLen: Int,
    _ msgPtr: UnsafePointer<UInt8>?, _ msgLen: Int,
    _ sigOut: UnsafeMutablePointer<UInt8>?, _ sigCap: Int,
    _ sigLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: bytes(blobPtr, blobLen)) else {
        return BCKS_ERR_INPUT
    }
    guard let sig = try? key.signature(for: bytes(msgPtr, msgLen)) else { return BCKS_ERR_INTERNAL }
    return emit(sig.derRepresentation, sigOut, sigCap, sigLenOut)
}
