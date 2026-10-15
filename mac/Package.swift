// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Padly",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "Padly", targets: ["Padly"]),
        .executable(name: "padly-smoke", targets: ["PadlySmoke"]),
    ],
    targets: [
        .executableTarget(
            name: "Padly",
            path: "Sources/Padly",
            resources: [.copy("BrowserResources")]
        ),
        // Standalone smoke-test client with its own protocol implementation —
        // doubles as an interop check against the server's codec/crypto.
        .executableTarget(
            name: "PadlySmoke",
            path: "Sources/PadlySmoke"
        ),
        .testTarget(
            name: "PadlyTests",
            dependencies: ["Padly"],
            path: "Tests/PadlyTests"
        ),
    ]
)
