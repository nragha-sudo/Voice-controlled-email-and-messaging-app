import Foundation
import Porcupine

enum WakeWordEvent {
    case wakePhraseDetected
    case stopPhraseDetected
}

protocol WakeWordEngineDelegate: AnyObject {
    func wakeWordEngine(_ engine: WakeWordEngine, didDetect event: WakeWordEvent)
    func wakeWordEngine(_ engine: WakeWordEngine, didFailWith error: Error)
}

/// Continuous, fully on-device wake/stop phrase detection using Picovoice
/// Porcupine. `PorcupineManager` owns its own low-overhead `AVAudioEngine`
/// tap, so this class is mostly bookkeeping: which of the two loaded
/// keyword models fired, and start/stop lifecycle so we're not spending any
/// CPU while `.asleep`.
final class WakeWordEngine {
    weak var delegate: WakeWordEngineDelegate?

    private var manager: PorcupineManager?
    private let wakeWordIndex = 0
    private let stopWordIndex = 1

    private(set) var isRunning = false

    /// - Parameters:
    ///   - accessKey: Picovoice Console AccessKey.
    ///   - wakeWordPath: absolute path to the "Hey Claude"-style .ppn model.
    ///   - stopWordPath: absolute path to the stop-phrase .ppn model.
    ///   - sensitivity: 0.0 (fewer false accepts) ... 1.0 (fewer misses), applied to both.
    func configure(accessKey: String, wakeWordPath: String, stopWordPath: String, sensitivity: Float) throws {
        stop()

        manager = try PorcupineManager(
            accessKey: accessKey,
            keywordPaths: [wakeWordPath, stopWordPath],
            sensitivities: [sensitivity, sensitivity],
            errorCallback: { [weak self] error in
                guard let self else { return }
                self.isRunning = false
                self.delegate?.wakeWordEngine(self, didFailWith: error)
            },
            keywordCallback: { [weak self] keywordIndex in
                guard let self else { return }
                switch Int(keywordIndex) {
                case self.wakeWordIndex:
                    self.delegate?.wakeWordEngine(self, didDetect: .wakePhraseDetected)
                case self.stopWordIndex:
                    self.delegate?.wakeWordEngine(self, didDetect: .stopPhraseDetected)
                default:
                    AppLog.error("Porcupine returned unexpected keyword index \(keywordIndex)")
                }
            }
        )
    }

    func start() {
        guard let manager, !isRunning else { return }
        do {
            try manager.start()
            isRunning = true
            AppLog.info("Wake word engine started")
        } catch {
            delegate?.wakeWordEngine(self, didFailWith: error)
        }
    }

    /// Temporarily pause listening for the wake/stop phrase while we're
    /// actively recording+transcribing an utterance, so Porcupine isn't
    /// competing with `DictationRecorder` for the microphone.
    func stop() {
        guard isRunning else { return }
        manager?.stop()
        isRunning = false
        AppLog.info("Wake word engine stopped")
    }

    func teardown() {
        stop()
        manager = nil
    }
}
