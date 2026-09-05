import AVFoundation
import Foundation

enum DictationRecorderError: Error {
    case engineStartFailed(Error)
    case noAudioCaptured
}

/// Records one dictated utterance to a temporary 16kHz mono WAV file (the
/// format whisper.cpp expects), starting the instant it's told to and
/// stopping automatically once the speaker pauses.
///
/// Ends a capture on whichever comes first:
///   - `silenceTimeout` seconds of RMS energy below `silenceThreshold`, or
///   - `maxDuration` seconds total (a hard safety cap), or
///   - `stop()` called explicitly (e.g. stop-phrase heard).
final class DictationRecorder {
    private let audioEngine = AVAudioEngine()
    private var audioFile: AVAudioFile?
    private var silenceTimer: Timer?
    private var maxDurationTimer: Timer?
    private var hasCapturedAudio = false

    private let silenceThreshold: Float = 0.012
    private var silenceTimeout: TimeInterval = 1.2
    private var maxDuration: TimeInterval = 30

    /// Called once when recording stops, with the URL of the captured WAV
    /// (nil on failure/no audio).
    private var completion: ((Result<URL, Error>) -> Void)?

    func record(
        silenceTimeout: TimeInterval,
        maxDuration: TimeInterval,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        self.silenceTimeout = silenceTimeout
        self.maxDuration = maxDuration
        self.completion = completion
        self.hasCapturedAudio = false

        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("dictation-\(UUID().uuidString).wav")

        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.inputFormat(forBus: 0)

        // whisper.cpp wants 16kHz mono PCM16. We tap the mic at its native
        // format and let AVAudioFile handle the conversion to that settings dict.
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ]

        do {
            let file = try AVAudioFile(forWriting: outputURL, settings: outputSettings)
            audioFile = file

            inputNode.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { [weak self] buffer, _ in
                self?.handle(buffer: buffer)
            }

            audioEngine.prepare()
            try audioEngine.start()
            armMaxDurationTimer()
            AppLog.info("Dictation recording started")
        } catch {
            cleanupTap()
            completion(.failure(DictationRecorderError.engineStartFailed(error)))
        }
    }

    func stop() {
        guard audioEngine.isRunning else { return }
        finish()
    }

    // MARK: - Buffer handling

    private func handle(buffer: AVAudioPCMBuffer) {
        appendToFile(buffer)

        let rms = Self.rms(of: buffer)
        if rms > silenceThreshold {
            hasCapturedAudio = true
            resetSilenceTimer()
        } else if hasCapturedAudio {
            // Below threshold, but only start the "they stopped talking"
            // clock once we've actually heard some voiced audio, so a
            // slow start to the utterance doesn't get cut off instantly.
            armSilenceTimerIfNeeded()
        }
    }

    private func appendToFile(_ buffer: AVAudioPCMBuffer) {
        do {
            try audioFile?.write(from: buffer)
        } catch {
            AppLog.error("Failed writing dictation audio: \(error)")
        }
    }

    private static func rms(of buffer: AVAudioPCMBuffer) -> Float {
        guard let channelData = buffer.floatChannelData else { return 0 }
        let frameCount = Int(buffer.frameLength)
        guard frameCount > 0 else { return 0 }
        var sum: Float = 0
        let samples = channelData[0]
        for i in 0..<frameCount {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / Float(frameCount))
    }

    // MARK: - Timers

    private func resetSilenceTimer() {
        silenceTimer?.invalidate()
        silenceTimer = nil
    }

    private func armSilenceTimerIfNeeded() {
        guard silenceTimer == nil else { return }
        let timer = Timer(timeInterval: silenceTimeout, repeats: false) { [weak self] _ in
            AppLog.info("Silence timeout reached, ending dictation")
            self?.finish()
        }
        silenceTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func armMaxDurationTimer() {
        maxDurationTimer?.invalidate()
        let timer = Timer(timeInterval: maxDuration, repeats: false) { [weak self] _ in
            AppLog.info("Max utterance duration reached, ending dictation")
            self?.finish()
        }
        maxDurationTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    // MARK: - Teardown

    private func finish() {
        let url = audioFile?.url
        let captured = hasCapturedAudio
        cleanupTap()

        guard let url, captured else {
            completion?(.failure(DictationRecorderError.noAudioCaptured))
            completion = nil
            return
        }
        completion?(.success(url))
        completion = nil
    }

    private func cleanupTap() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        maxDurationTimer?.invalidate()
        maxDurationTimer = nil
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        audioEngine.inputNode.removeTap(onBus: 0)
        audioFile = nil
    }
}
