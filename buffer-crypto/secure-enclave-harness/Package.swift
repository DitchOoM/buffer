// swift-tools-version:5.9
import PackageDescription

// On-device test harness for the Secure Enclave path of buffer-crypto's CryptoKit shim.
//
// `Sources/CryptoKitShim/CryptoKitShim.swift` is a SYMLINK to the real shipped shim
// (`../src/nativeInterop/swift/CryptoKitShim.swift`), so this test always exercises the exact
// `@_cdecl` functions the Kotlin/Native `appleMain` actuals bind — it cannot drift from a copy.
//
// Run:
//   swift test                                              # macOS host (Enclave XCTSkips if the
//                                                           # xctest bundle is unentitled — proves
//                                                           # the harness compiles + the skip path)
//   xcodebuild test -scheme SecureEnclaveHarness-Package \  # real Secure Enclave on a connected
//     -destination 'platform=iOS,id=<device-udid>'          # iPhone (your dev signing)
let package = Package(
    name: "SecureEnclaveHarness",
    platforms: [.macOS(.v11), .iOS(.v14), .tvOS(.v14), .watchOS(.v7)],
    targets: [
        .target(name: "CryptoKitShim"),
        .testTarget(
            name: "SecureEnclaveHarnessTests",
            dependencies: ["CryptoKitShim"],
        ),
    ],
)
