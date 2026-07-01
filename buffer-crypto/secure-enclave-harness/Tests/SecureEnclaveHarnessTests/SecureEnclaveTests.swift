import CryptoKit
import Foundation
import XCTest

@testable import CryptoKitShim

/// Guards the Secure Enclave path of the shipped CryptoKit shim (`bcks_secure_enclave_*`), which the
/// Kotlin/Native `appleMain` provider binds. GitHub-hosted CI cannot mint Enclave keys (an unsigned
/// xctest bundle hits `errSecMissingEntitlement`, and the simulator has no Enclave), so on those
/// hosts the tests `XCTSkip` rather than fail. Run on a connected iPhone (with dev signing) via
/// `xcodebuild test -destination 'platform=iOS,id=<udid>'` to actually exercise the secure element.
final class SecureEnclaveTests: XCTestCase {
    private let blobCap = 1024
    private let pointLen = 65 // uncompressed SEC1 P-256 point (04 ‖ X ‖ Y)
    private let sigCap = 80 // max P-256 DER ECDSA signature

    /// Generates an Enclave key, signs, and verifies the DER signature against the returned public
    /// point with CryptoKit — proving generate → sign wired through to a real, standard signature.
    func testEnclaveGenerateSignVerifyRoundTrips() throws {
        try skipUnlessEnclaveUsable()
        let (blob, point) = try generate()

        let message = Array("secure enclave harness round-trip".utf8)
        let signature = try sign(blob: blob, message: message)

        let publicKey = try P256.Signing.PublicKey(x963Representation: Data(point))
        let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: Data(signature))
        XCTAssertTrue(publicKey.isValidSignature(ecdsa, for: Data(message)), "hw-sign must verify under the Enclave's public key")
        XCTAssertFalse(publicKey.isValidSignature(ecdsa, for: Data("a different message".utf8)), "signature must not verify a different message")
    }

    /// Each generation yields a distinct key (the Enclave mints fresh material, never reuses).
    func testEachGenerationIsDistinct() throws {
        try skipUnlessEnclaveUsable()
        let (_, point1) = try generate()
        let (_, point2) = try generate()
        XCTAssertNotEqual(point1, point2, "each Enclave key generation must produce a fresh key")
    }

    /// Tier-2 binding negative test (no human, no prompt): a key generated with a user-presence
    /// `SecAccessControl` must be REFUSED when signing through an LAContext that is forbidden from
    /// prompting (`interactionNotAllowed`). If this sign succeeds, the access control was never
    /// applied at generation and the biometric gate is advisory-only again.
    func testAccessControlledKeyRefusesSigningWithoutInteraction() throws {
        try skipUnlessEnclaveUsable()

        // authReq 1 = userPresence (biometric or device credential).
        var blob = [UInt8](repeating: 0, count: blobCap)
        var blobLen = 0
        var point = [UInt8](repeating: 0, count: pointLen)
        var pLen = 0
        let genStatus = bcks_secure_enclave_p256_generate_ac(1, &blob, blobCap, &blobLen, &point, pointLen, &pLen)
        guard genStatus == 0 else {
            // A passcode-less host cannot mint user-presence keys — that is itself the binding
            // working; nothing further to assert headlessly.
            throw XCTSkip("access-controlled generate unavailable here (status \(genStatus)) — likely no passcode/biometry enrolled")
        }
        let keyBlob = Array(blob.prefix(blobLen))

        let ctx = bcks_la_context_create("harness binding test")
        XCTAssertGreaterThan(ctx, 0, "LocalAuthentication must be available on this platform")
        defer { bcks_la_context_release(ctx) }

        // Forbid interaction, then evaluate: must fail rather than prompt.
        XCTAssertNotEqual(bcks_la_evaluate(ctx, 1, 0), 0, "evaluate with interactionNotAllowed must fail, not prompt")

        // Signing through the never-evaluated, interaction-forbidden context must be refused by
        // the Enclave's access control (BCKS_ERR_AUTH = -2), never emit a signature.
        let message = Array("must not sign".utf8)
        var sig = [UInt8](repeating: 0, count: sigCap)
        var sigLen = 0
        let signStatus = keyBlob.withUnsafeBufferPointer { blobPtr in
            message.withUnsafeBufferPointer { msgPtr in
                bcks_secure_enclave_p256_sign_ctx(blobPtr.baseAddress, keyBlob.count, ctx, msgPtr.baseAddress, message.count, &sig, sigCap, &sigLen)
            }
        }
        XCTAssertEqual(signStatus, -2, "an access-controlled key must refuse an unauthenticated sign (got status \(signStatus))")
        XCTAssertEqual(sigLen, 0, "no signature bytes may be emitted on refusal")
    }

    // MARK: - shim drivers

    private func skipUnlessEnclaveUsable() throws {
        guard bcks_secure_enclave_available() == 1 else {
            throw XCTSkip("Secure Enclave is not available on this host (simulator / no enclave)")
        }
        // Presence != usability: an unentitled/unsigned bundle cannot mint Enclave keys. Probe once.
        var blob = [UInt8](repeating: 0, count: blobCap)
        var blobLen = 0
        var point = [UInt8](repeating: 0, count: pointLen)
        var pLen = 0
        let status = bcks_secure_enclave_p256_generate(&blob, blobCap, &blobLen, &point, pointLen, &pLen)
        guard status == 0 else {
            throw XCTSkip("Secure Enclave present but not usable here (status \(status)) — likely an unentitled binary; run on a signed device")
        }
    }

    private func generate() throws -> (blob: [UInt8], point: [UInt8]) {
        var blob = [UInt8](repeating: 0, count: blobCap)
        var blobLen = 0
        var point = [UInt8](repeating: 0, count: pointLen)
        var pLen = 0
        let status = bcks_secure_enclave_p256_generate(&blob, blobCap, &blobLen, &point, pointLen, &pLen)
        XCTAssertEqual(status, 0, "generate must succeed")
        XCTAssertGreaterThan(blobLen, 0, "blob must be non-empty")
        XCTAssertLessThan(blobLen, blobCap, "blob must fit the cap the Kotlin provider uses")
        XCTAssertEqual(pLen, pointLen, "public point must be a 65-byte uncompressed SEC1 point")
        return (Array(blob.prefix(blobLen)), Array(point.prefix(pLen)))
    }

    private func sign(blob: [UInt8], message: [UInt8]) throws -> [UInt8] {
        var sig = [UInt8](repeating: 0, count: sigCap)
        var sigLen = 0
        let status = blob.withUnsafeBufferPointer { blobPtr in
            message.withUnsafeBufferPointer { msgPtr in
                bcks_secure_enclave_p256_sign(blobPtr.baseAddress, blob.count, msgPtr.baseAddress, message.count, &sig, sigCap, &sigLen)
            }
        }
        XCTAssertEqual(status, 0, "sign must succeed")
        XCTAssertGreaterThan(sigLen, 0, "signature must be non-empty")
        return Array(sig.prefix(sigLen))
    }
}
