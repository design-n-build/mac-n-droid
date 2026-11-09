// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "MacNDroid",
    platforms: [.macOS(.v14)],
    dependencies: [
        // Automatic updates for the downloaded (non–App Store) app.
        .package(url: "https://github.com/sparkle-project/Sparkle", from: "2.9.6"),
    ],
    targets: [
        .executableTarget(
            name: "MacNDroid",
            dependencies: [.product(name: "Sparkle", package: "Sparkle")],
            path: "Sources/MacNDroid",
            linkerSettings: [
                // Sparkle.framework is copied into the app's Contents/Frameworks by scripts/build-app.sh.
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"]),
            ]
        )
    ]
)
