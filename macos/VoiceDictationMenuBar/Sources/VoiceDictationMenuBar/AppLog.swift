import Foundation
import os

/// Thin wrapper around os.Logger so every subsystem logs consistently and
/// shows up under one predicate in Console.app (subsystem == the bundle id).
enum AppLog {
    private static let logger = Logger(subsystem: "com.example.voicedictationmenubar", category: "app")

    static func info(_ message: String) {
        logger.info("\(message, privacy: .public)")
    }

    static func error(_ message: String) {
        logger.error("\(message, privacy: .public)")
    }

    static func debug(_ message: String) {
        logger.debug("\(message, privacy: .public)")
    }
}
