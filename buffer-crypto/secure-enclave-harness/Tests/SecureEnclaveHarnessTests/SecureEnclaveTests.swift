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
