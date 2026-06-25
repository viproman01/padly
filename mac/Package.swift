// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Padly",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "Padly", targets: ["Padly"]),
    ],
    targets: [
        .executableTarget(
            name: "Padly",
            path: "Sources/Padly"
        ),
        .testTarget(
            name: "PadlyTests",
            dependencies: ["Padly"],
            path: "Tests/PadlyTests"
        ),
    ]
)
