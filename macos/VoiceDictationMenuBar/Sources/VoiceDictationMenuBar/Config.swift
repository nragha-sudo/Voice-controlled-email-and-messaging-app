import Foundation

/// User-editable runtime configuration, loaded from
/// `~/Library/Application Support/VoiceDictationMenuBar/config.json`.
///
/// The file is created with defaults on first launch if it doesn't exist,
/// so the app is usable out of the box (aside from the Porcupine access key,
/// which every user must supply themselves per Picovoice's licensing terms).
struct AppConfig: Codable {
    /// Picovoice AccessKey from https://console.picovoice.ai/
    var porcupineAccessKey: String

    /// Filenames (inside Resources/WakeWords) of the trained wake word models.
    /// Default expects a "Hey Claude" model exported from the Picovoice Console.
    var wakeWordFile: String

    /// Stop-phrase wake word model, used to end an active session / sleep the app.
    var stopWordFile: String

    /// Path to a compiled whisper.cpp CLI binary (the `whisper-cli`/`main` binary
    /// produced by `make` in the whisper.cpp repo). Left blank to use the
    /// built-in on-device Speech framework fallback instead.
    var whisperCppBinaryPath: String

    /// Path to a whisper.cpp GGML model file (e.g. ggml-base.en.bin).
    var whisperModelPath: String

    /// Seconds of trailing silence that ends a dictation capture.
    var silenceTimeoutSeconds: Double

    /// Maximum seconds a single dictation capture may run.
    var maxUtteranceSeconds: Double

    /// Minutes of no wake-word activity before the app auto-sleeps.
    var autoSleepMinutes: Double

    /// Porcupine wake word sensitivity, 0.0 (fewer false accepts) - 1.0 (fewer misses).
    var wakeWordSensitivity: Float

    static let `default` = AppConfig(
        porcupineAccessKey: "",
        wakeWordFile: "hey-claude_mac.ppn",
        stopWordFile: "stop-listening_mac.ppn",
        whisperCppBinaryPath: "",
        whisperModelPath: "",
        silenceTimeoutSeconds: 1.2,
        maxUtteranceSeconds: 30,
        autoSleepMinutes: 30,
        wakeWordSensitivity: 0.6
    )

    static var configDirectory: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return appSupport.appendingPathComponent("VoiceDictationMenuBar", isDirectory: true)
    }

    static var configFileURL: URL {
        configDirectory.appendingPathComponent("config.json")
    }

    static func loadOrCreateDefault() -> AppConfig {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: configDirectory, withIntermediateDirectories: true)
            if fm.fileExists(atPath: configFileURL.path) {
                let data = try Data(contentsOf: configFileURL)
                return try JSONDecoder().decode(AppConfig.self, from: data)
            } else {
                try AppConfig.default.save()
                return .default
            }
        } catch {
            AppLog.error("Failed to load config, using in-memory defaults: \(error)")
            return .default
        }
    }

    func save() throws {
        let data = try JSONEncoder.pretty.encode(self)
        try FileManager.default.createDirectory(at: AppConfig.configDirectory, withIntermediateDirectories: true)
        try data.write(to: AppConfig.configFileURL, options: .atomic)
    }
}

private extension JSONEncoder {
    static var pretty: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }
}
