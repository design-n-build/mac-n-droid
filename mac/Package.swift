// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "MacNDroid",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "MacNDroid",
            path: "Sources/MacNDroid"
        )
    ]
)
