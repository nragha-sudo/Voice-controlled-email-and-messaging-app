// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "VoiceDictationMenuBar",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "VoiceDictationMenuBar", targets: ["VoiceDictationMenuBar"])
    ],
    dependencies: [
        // Picovoice Porcupine on-device wake word engine (Swift bindings).
        // Requires network access to resolve when opening this package in Xcode.
        .package(url: "https://github.com/Picovoice/porcupine.git", from: "3.0.0")
    ],
    targets: [
        .executableTarget(
            name: "VoiceDictationMenuBar",
            dependencies: [
                .product(name: "Porcupine", package: "porcupine")
            ],
            path: "Sources/VoiceDictationMenuBar",
            resources: [
                .copy("../../Resources/WakeWords")
            ],
            linkerSettings: [
                // Embeds Info.plist keys (mic usage description, LSUIElement, etc.)
                // directly into the raw SPM executable so permission prompts show
                // correct text even before it's wrapped into a proper .app bundle
                // by Scripts/build_app_bundle.sh.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Sources/VoiceDictationMenuBar/Supporting/Info.plist"
                ])
            ]
        )
    ]
)
