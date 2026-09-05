import Foundation
import Speech

protocol Transcriber {
    /// Transcribes a 16kHz mono WAV file at `audioURL` and calls `completion`
    /// on an arbitrary background queue with the recognized text (trimmed,
    /// may be empty if nothing was understood).
    func transcribe(audioURL: URL, completion: @escaping (Result<String, Error>) -> Void)
}

enum TranscriberError: Error {
    case binaryNotFound(String)
    case processFailed(status: Int32, stderr: String)
    case emptyOutput
    case onDeviceRecognitionUnavailable
}

/// Primary transcriber: shells out to a locally-built whisper.cpp CLI
/// (`whisper-cli` / `main`, depending on your whisper.cpp checkout) running
/// entirely offline against a local GGML model file. Nothing here ever
/// touches the network.
///
/// Build whisper.cpp yourself (see the top-level README) and point
/// `binaryPath`/`modelPath` at the results via the app's config file.
final class WhisperCppTranscriber: Transcriber {
    let binaryPath: String
    let modelPath: String

    init(binaryPath: String, modelPath: String) {
        self.binaryPath = binaryPath
        self.modelPath = modelPath
    }

    func transcribe(audioURL: URL, completion: @escaping (Result<String, Error>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard FileManager.default.isExecutableFile(atPath: self.binaryPath) else {
                completion(.failure(TranscriberError.binaryNotFound(self.binaryPath)))
                return
            }

            let outputStem = audioURL.deletingPathExtension().path

            let process = Process()
            process.executableURL = URL(fileURLWithPath: self.binaryPath)
            process.arguments = [
                "-m", self.modelPath,
                "-f", audioURL.path,
                "-otxt",
                "-of", outputStem,
                "-nt",              // no timestamps in the plain-text output
                "-l", "en",
                "--no-prints"
            ]

            let stderrPipe = Pipe()
            process.standardError = stderrPipe
            process.standardOutput = Pipe()

            do {
                try process.run()
                process.waitUntilExit()
            } catch {
                completion(.failure(error))
                return
            }

            guard process.terminationStatus == 0 else {
                let stderrData = stderrPipe.fileHandleForReading.readDataToEndOfFile()
                let stderrText = String(data: stderrData, encoding: .utf8) ?? ""
                completion(.failure(TranscriberError.processFailed(status: process.terminationStatus, stderr: stderrText)))
                return
            }

            let txtURL = URL(fileURLWithPath: outputStem + ".txt")
            guard let text = try? String(contentsOf: txtURL, encoding: .utf8) else {
                completion(.failure(TranscriberError.emptyOutput))
                return
            }
            try? FileManager.default.removeItem(at: txtURL)
            try? FileManager.default.removeItem(at: audioURL)

            completion(.success(text.trimmingCharacters(in: .whitespacesAndNewlines)))
        }
    }
}

/// Fallback transcriber using Apple's built-in Speech framework with
/// `requiresOnDeviceRecognition = true`, so it stays fully local like the
/// whisper.cpp path. Used automatically when no whisper.cpp binary/model is
/// configured, so the app is usable immediately without any extra setup —
/// swap to `WhisperCppTranscriber` in the config for the higher-accuracy
/// engine described in the spec.
final class OnDeviceSpeechTranscriber: NSObject, Transcriber {
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))

    func transcribe(audioURL: URL, completion: @escaping (Result<String, Error>) -> Void) {
        guard let recognizer, recognizer.isAvailable else {
            completion(.failure(TranscriberError.onDeviceRecognitionUnavailable))
            return
        }
        guard recognizer.supportsOnDeviceRecognition else {
            completion(.failure(TranscriberError.onDeviceRecognitionUnavailable))
            return
        }

        let request = SFSpeechURLRecognitionRequest(url: audioURL)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false

        recognizer.recognitionTask(with: request) { result, error in
            if let error {
                completion(.failure(error))
                return
            }
            guard let result, result.isFinal else { return }
            try? FileManager.default.removeItem(at: audioURL)
            completion(.success(result.bestTranscription.formattedString))
        }
    }

    static func requestAuthorization(completion: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            completion(status == .authorized)
        }
    }
}
