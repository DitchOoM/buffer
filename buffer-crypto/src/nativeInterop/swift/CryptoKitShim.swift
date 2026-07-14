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
#if canImport(LocalAuthentication) && !os(tvOS)
// tvOS ships the framework for canImport purposes but marks LAContext itself unavailable,
// so the os() clause is load-bearing.
import LocalAuthentication
#endif

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

// Build a Data over a (pointer, length) pair. Data(bytes:count:) COPIES the bytes — this is a
// staging copy, not a zero-copy view. The copy is released (not zeroed) when the Data goes out of
// scope; Kotlin-side wiping covers only the caller's buffer, not this transient.
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
// ECDH X9.63 reconstruction from a bare scalar — CryptoKit P256/P384/P521.KeyAgreement
// =============================================================================
//
// The key-agreement private encoding is the raw big-endian scalar on every platform. Apple's
// Security-framework agreement (SecKeyCreateWithData) needs the full X9.63 private representation
// (04 ‖ X ‖ Y ‖ K) and cannot derive the public point from the scalar, so this reconstructs it via
// CryptoKit's P###.KeyAgreement.PrivateKey(rawRepresentation:).x963Representation just before the
// Security-framework exchange.

private func ecdhX963(_ curve: Int32, _ scalar: Data) -> Data? {
    switch curve {
    case 256:
        return (try? P256.KeyAgreement.PrivateKey(rawRepresentation: scalar))?.x963Representation
    case 384:
        return (try? P384.KeyAgreement.PrivateKey(rawRepresentation: scalar))?.x963Representation
    case 521:
        return (try? P521.KeyAgreement.PrivateKey(rawRepresentation: scalar))?.x963Representation
    default:
        return nil
    }
}

// Emit the X9.63 private representation (04 ‖ X ‖ Y ‖ K) for a curve's raw scalar (256/384/521).
@_cdecl("bcks_ecdh_x963_from_scalar")
public func bcks_ecdh_x963_from_scalar(
    _ curve: Int32,
    _ scalarPtr: UnsafePointer<UInt8>?, _ scalarLen: Int,
    _ x963Out: UnsafeMutablePointer<UInt8>?, _ x963Cap: Int,
    _ x963LenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let x963 = ecdhX963(curve, bytes(scalarPtr, scalarLen)) else {
        return BCKS_ERR_INPUT
    }
    return emit(x963, x963Out, x963Cap, x963LenOut)
}

// =============================================================================
// EC point decompression — CryptoKit P256/P384/P521.KeyAgreement
// =============================================================================
//
// Recovers the uncompressed point (04 ‖ X ‖ Y) from a SEC1 compressed point (02/03 ‖ X). CryptoKit's
// compressed-point initializers require macOS 13 / iOS 16 / watchOS 9 / tvOS 16; on older systems the
// caller is told (BCKS_ERR_INTERNAL) so it can surface a capability error rather than a wrong answer.

@available(macOS 13.0, iOS 16.0, watchOS 9.0, tvOS 16.0, *)
private func ecDecompress(_ curve: Int32, _ point: Data) -> Data? {
    switch curve {
    case 256:
        return (try? P256.KeyAgreement.PublicKey(compressedRepresentation: point))?.x963Representation
    case 384:
        return (try? P384.KeyAgreement.PublicKey(compressedRepresentation: point))?.x963Representation
    case 521:
        return (try? P521.KeyAgreement.PublicKey(compressedRepresentation: point))?.x963Representation
    default:
        return nil
    }
}

@_cdecl("bcks_ec_decompress")
public func bcks_ec_decompress(
    _ curve: Int32,
    _ pointPtr: UnsafePointer<UInt8>?, _ pointLen: Int,
    _ out: UnsafeMutablePointer<UInt8>?, _ outCap: Int,
    _ outLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard #available(macOS 13.0, iOS 16.0, watchOS 9.0, tvOS 16.0, *) else {
        return BCKS_ERR_INTERNAL // CryptoKit lacks compressed-point support on this OS
    }
    guard let uncompressed = ecDecompress(curve, bytes(pointPtr, pointLen)) else {
        return BCKS_ERR_INPUT // off-curve / malformed compressed point
    }
    return emit(uncompressed, out, outCap, outLenOut)
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

// =============================================================================
// User-authentication binding — LocalAuthentication contexts + Secure Enclave
// access control. LocalAuthentication does not exist on every Apple platform
// (no tvOS), so the whole surface is canImport-guarded and degrades to typed
// "unsupported" statuses instead of failing to compile the Kotlin actuals.
// =============================================================================

#if canImport(LocalAuthentication) && !os(tvOS)
// Live LAContexts handed to Kotlin as opaque Int64 handles. Kotlin owns the
// lifecycle (create/release); the registry just keeps ARC references alive and
// remembers the localized reason to show in the OS prompt.
private final class LAContextRegistry {
    static let shared = LAContextRegistry()
    private var next: Int64 = 1
    private var entries: [Int64: (LAContext, String)] = [:]
    private let lock = NSLock()

    func create(reason: String) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        let handle = next
        next += 1
        entries[handle] = (LAContext(), reason)
        return handle
    }

    func get(_ handle: Int64) -> (LAContext, String)? {
        lock.lock(); defer { lock.unlock() }
        return entries[handle]
    }

    func release(_ handle: Int64) {
        lock.lock(); defer { lock.unlock() }
        if let (ctx, _) = entries.removeValue(forKey: handle) { ctx.invalidate() }
    }
}
#endif

// Create a LocalAuthentication context with the user-facing reason string shown in the OS prompt.
// Returns an opaque handle (> 0), or 0 when LocalAuthentication is unavailable on this platform.
@_cdecl("bcks_la_context_create")
public func bcks_la_context_create(_ reasonPtr: UnsafePointer<CChar>?) -> Int64 {
    #if canImport(LocalAuthentication) && !os(tvOS)
    let reason = reasonPtr.map { String(cString: $0) } ?? ""
    return LAContextRegistry.shared.create(reason: reason)
    #else
    return 0
    #endif
}

// Invalidate and release a context created by bcks_la_context_create. Idempotent.
@_cdecl("bcks_la_context_release")
public func bcks_la_context_release(_ handle: Int64) {
    #if canImport(LocalAuthentication) && !os(tvOS)
    LAContextRegistry.shared.release(handle)
    #endif
}

// Evaluate the context's auth policy, prompting the user. method: 1 = biometric or device
// credential, 2 = biometric only. interactionAllowed == 0 sets interactionNotAllowed so a
// headless caller fails (BCKS_ERR_AUTH) instead of hanging on an invisible prompt. BLOCKING —
// call off the main thread. A successfully evaluated context can then authorize Enclave signs
// (bcks_secure_enclave_p256_sign_ctx) without re-prompting until released.
@_cdecl("bcks_la_evaluate")
public func bcks_la_evaluate(_ handle: Int64, _ method: Int32, _ interactionAllowed: Int32) -> Int32 {
    #if canImport(LocalAuthentication) && !os(tvOS)
    guard let (ctx, reason) = LAContextRegistry.shared.get(handle) else { return BCKS_ERR_INPUT }
    #if os(watchOS)
    // watchOS exposes no discrete biometric policy (wrist detection stands in for biometrics);
    // deviceOwnerAuthentication is the strongest policy the platform offers for either method.
    let policy: LAPolicy = .deviceOwnerAuthentication
    #else
    let policy: LAPolicy = method == 2 ? .deviceOwnerAuthenticationWithBiometrics : .deviceOwnerAuthentication
    #endif
    ctx.interactionNotAllowed = interactionAllowed == 0
    let sem = DispatchSemaphore(value: 0)
    var ok = false
    ctx.evaluatePolicy(policy, localizedReason: reason.isEmpty ? "authorize hardware key use" : reason) { success, _ in
        ok = success
        sem.signal()
    }
    sem.wait()
    return ok ? BCKS_OK : BCKS_ERR_AUTH
    #else
    return BCKS_ERR_INTERNAL
    #endif
}

// Generate a Secure Enclave P-256 signing key bound to user authentication via SecAccessControl.
// authReq: 1 = userPresence (biometric or device credential), 2 = biometryCurrentSet (biometric
// only; the OS invalidates the key when biometric enrollment changes). Same outputs as
// bcks_secure_enclave_p256_generate.
@_cdecl("bcks_secure_enclave_p256_generate_ac")
public func bcks_secure_enclave_p256_generate_ac(
    _ authReq: Int32,
    _ blobOut: UnsafeMutablePointer<UInt8>?, _ blobCap: Int,
    _ blobLenOut: UnsafeMutablePointer<Int>?,
    _ pointOut: UnsafeMutablePointer<UInt8>?, _ pointCap: Int,
    _ pointLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard SecureEnclave.isAvailable else { return BCKS_ERR_INTERNAL }
    var flags: SecAccessControlCreateFlags = [.privateKeyUsage]
    switch authReq {
    case 1: flags.insert(.userPresence)
    case 2: flags.insert(.biometryCurrentSet)
    default: return BCKS_ERR_INPUT
    }
    var acError: Unmanaged<CFError>?
    guard let ac = SecAccessControlCreateWithFlags(
        kCFAllocatorDefault, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, flags, &acError
    ) else { return BCKS_ERR_INTERNAL }
    guard let key = try? SecureEnclave.P256.Signing.PrivateKey(accessControl: ac) else { return BCKS_ERR_INTERNAL }
    let s = emit(key.dataRepresentation, blobOut, blobCap, blobLenOut)
    if s != BCKS_OK { return s }
    return emit(key.publicKey.x963Representation, pointOut, pointCap, pointLenOut)
}

// Classify a signing failure on an access-controlled key: user-auth denials (cancel, no match,
// interaction not allowed, auth failed) map to BCKS_ERR_AUTH; everything else is internal.
private func classifySignFailure(_ error: Error) -> Int32 {
    let ns = error as NSError
    #if canImport(LocalAuthentication) && !os(tvOS)
    if ns.domain == LAError.errorDomain { return BCKS_ERR_AUTH }
    #endif
    if ns.domain == NSOSStatusErrorDomain {
        switch OSStatus(ns.code) {
        case errSecAuthFailed, errSecUserCanceled, errSecInteractionNotAllowed: return BCKS_ERR_AUTH
        default: return BCKS_ERR_INTERNAL
        }
    }
    return BCKS_ERR_INTERNAL
}

// Sign with the Enclave key reconstructed from its blob, authorizing through the LAContext
// behind laHandle (0 = no context: the OS drives any required prompt itself, or refuses when it
// cannot). Emits a DER signature; BCKS_ERR_AUTH when user authentication was denied.
@_cdecl("bcks_secure_enclave_p256_sign_ctx")
public func bcks_secure_enclave_p256_sign_ctx(
    _ blobPtr: UnsafePointer<UInt8>?, _ blobLen: Int,
    _ laHandle: Int64,
    _ msgPtr: UnsafePointer<UInt8>?, _ msgLen: Int,
    _ sigOut: UnsafeMutablePointer<UInt8>?, _ sigCap: Int,
    _ sigLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    #if canImport(LocalAuthentication) && !os(tvOS)
    let ctx: LAContext? = laHandle == 0 ? nil : LAContextRegistry.shared.get(laHandle)?.0
    if laHandle != 0 && ctx == nil { return BCKS_ERR_INPUT }
    // The context-carrying init needs watchOS 9; below that (floor: 7) sign without one — the
    // access control still holds, the OS just drives any prompt itself.
    guard #available(watchOS 9.0, iOS 13.0, macOS 10.15, *) else {
        guard let key = try? SecureEnclave.P256.Signing.PrivateKey(
            dataRepresentation: bytes(blobPtr, blobLen)
        ) else { return BCKS_ERR_INPUT }
        do {
            let sig = try key.signature(for: bytes(msgPtr, msgLen))
            return emit(sig.derRepresentation, sigOut, sigCap, sigLenOut)
        } catch {
            return classifySignFailure(error)
        }
    }
    guard let key = try? SecureEnclave.P256.Signing.PrivateKey(
        dataRepresentation: bytes(blobPtr, blobLen), authenticationContext: ctx
    ) else { return BCKS_ERR_INPUT }
    #else
    guard let key = try? SecureEnclave.P256.Signing.PrivateKey(
        dataRepresentation: bytes(blobPtr, blobLen)
    ) else { return BCKS_ERR_INPUT }
    #endif
    do {
        let sig = try key.signature(for: bytes(msgPtr, msgLen))
        return emit(sig.derRepresentation, sigOut, sigCap, sigLenOut)
    } catch {
        return classifySignFailure(error)
    }
}

// =============================================================================
// Keychain persistence — durable generic-password items for the alias-addressable key store
// =============================================================================
//
// The durable AppleKeychainKeyStore holds each key's framed (public point + Enclave restore blob) in
// a generic-password Keychain item keyed by (service = store name, account = alias). The stored value
// is NOT a private key — the Secure Enclave holds the private scalar; the blob is an encrypted
// representation only the same Enclave can restore (see the Secure Enclave section). Items are
// kSecAttrAccessibleWhenUnlockedThisDeviceOnly, so they never sync off-device and are unreadable
// while the device is locked. Only strings/bytes cross the C-ABI boundary; no CFType is exposed.

private func keychainBaseQuery(_ service: UnsafePointer<CChar>?, _ account: UnsafePointer<CChar>?) -> [String: Any] {
    var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword]
    if let service = service { query[kSecAttrService as String] = String(cString: service) }
    if let account = account { query[kSecAttrAccount as String] = String(cString: account) }
    return query
}

@_cdecl("bcks_keychain_put")
public func bcks_keychain_put(
    _ service: UnsafePointer<CChar>?, _ account: UnsafePointer<CChar>?,
    _ dataPtr: UnsafePointer<UInt8>?, _ dataLen: Int
) -> Int32 {
    var query = keychainBaseQuery(service, account)
    // Replace-any-existing: delete then add, so a re-put under the same alias is idempotent.
    SecItemDelete(query as CFDictionary)
    query[kSecValueData as String] = bytes(dataPtr, dataLen)
    query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    return SecItemAdd(query as CFDictionary, nil) == errSecSuccess ? BCKS_OK : BCKS_ERR_INTERNAL
}

@_cdecl("bcks_keychain_get")
public func bcks_keychain_get(
    _ service: UnsafePointer<CChar>?, _ account: UnsafePointer<CChar>?,
    _ out: UnsafeMutablePointer<UInt8>?, _ outCap: Int, _ outLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    var query = keychainBaseQuery(service, account)
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecItemNotFound { return BCKS_ERR_INPUT }
    guard status == errSecSuccess, let data = item as? Data else { return BCKS_ERR_INTERNAL }
    return emit(data, out, outCap, outLenOut)
}

@_cdecl("bcks_keychain_contains")
public func bcks_keychain_contains(_ service: UnsafePointer<CChar>?, _ account: UnsafePointer<CChar>?) -> Int32 {
    var query = keychainBaseQuery(service, account)
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    switch SecItemCopyMatching(query as CFDictionary, nil) {
    case errSecSuccess: return 1
    case errSecItemNotFound: return 0
    default: return BCKS_ERR_INTERNAL
    }
}

@_cdecl("bcks_keychain_delete")
public func bcks_keychain_delete(_ service: UnsafePointer<CChar>?, _ account: UnsafePointer<CChar>?) -> Int32 {
    switch SecItemDelete(keychainBaseQuery(service, account) as CFDictionary) {
    case errSecSuccess: return BCKS_OK
    case errSecItemNotFound: return BCKS_ERR_INPUT
    default: return BCKS_ERR_INTERNAL
    }
}

@_cdecl("bcks_keychain_aliases")
public func bcks_keychain_aliases(
    _ service: UnsafePointer<CChar>?,
    _ out: UnsafeMutablePointer<UInt8>?, _ outCap: Int, _ outLenOut: UnsafeMutablePointer<Int>?
) -> Int32 {
    var query = keychainBaseQuery(service, nil)
    query[kSecMatchLimit as String] = kSecMatchLimitAll
    query[kSecReturnAttributes as String] = true
    var items: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &items)
    if status == errSecItemNotFound { outLenOut?.pointee = 0; return BCKS_OK }
    guard status == errSecSuccess, let array = items as? [[String: Any]] else { return BCKS_ERR_INTERNAL }
    let names = array.compactMap { $0[kSecAttrAccount as String] as? String }
    return emit(Data(names.joined(separator: "\n").utf8), out, outCap, outLenOut)
}
