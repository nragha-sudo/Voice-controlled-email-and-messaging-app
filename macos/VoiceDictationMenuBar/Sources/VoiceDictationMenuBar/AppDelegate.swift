import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var config: AppConfig!
    private var stateController: AppStateController!
    private var wakeWordEngine: WakeWordEngine!
    private var dictationRecorder: DictationRecorder!
    private var transcriber: Transcriber!
    private var statusItemController: StatusItemController!

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu bar-only app: no Dock icon, no app menu. (Also set via
        // LSUIElement in Info.plist once packaged as a real .app bundle;
        // set here too so it behaves the same run via `swift run`.)
        NSApp.setActivationPolicy(.accessory)

        config = AppConfig.loadOrCreateDefault()
        stateController = AppStateController(autoSleepMinutes: config.autoSleepMinutes)
        stateController.addObserver(self)

        statusItemController = StatusItemController()
        statusItemController.delegate = self
        stateController.addObserver(statusItemController)

        dictationRecorder = DictationRecorder()
        transcriber = makeTranscriber(from: config)

        wakeWordEngine = WakeWordEngine()
        wakeWordEngine.delegate = self

        PermissionsManager.ensurePermissions { [weak self] granted in
            guard let self else { return }
            if granted {
                self.startUp()
            } else {
                AppLog.error("Required permissions not granted; app will remain asleep until relaunched with permissions in place")
            }
        }
    }

    private func makeTranscriber(from config: AppConfig) -> Transcriber {
        if !config.whisperCppBinaryPath.isEmpty, !config.whisperModelPath.isEmpty {
            AppLog.info("Using whisper.cpp transcriber")
            return WhisperCppTranscriber(binaryPath: config.whisperCppBinaryPath, modelPath: config.whisperModelPath)
        }
        AppLog.info("No whisper.cpp binary configured; using on-device Speech framework fallback")
        OnDeviceSpeechTranscriber.requestAuthorization { granted in
            if !granted {
                AppLog.error("Speech recognition authorization was not granted")
            }
        }
        return OnDeviceSpeechTranscriber()
    }

    private func startUp() {
        guard !config.porcupineAccessKey.isEmpty else {
            AppLog.error("No Porcupine access key configured — edit \(AppConfig.configFileURL.path) and relaunch")
            presentMissingAccessKeyAlert()
            return
        }

        let wakeWordPath = resourceURL(for: config.wakeWordFile)?.path ?? config.wakeWordFile
        let stopWordPath = resourceURL(for: config.stopWordFile)?.path ?? config.stopWordFile

        do {
            try wakeWordEngine.configure(
                accessKey: config.porcupineAccessKey,
                wakeWordPath: wakeWordPath,
                stopWordPath: stopWordPath,
                sensitivity: config.wakeWordSensitivity
            )
            wakeWordEngine.start()
            stateController.startListening()
        } catch {
            AppLog.error("Failed to start wake word engine: \(error)")
        }
    }

    private func resourceURL(for filename: String) -> URL? {
        Bundle.module.url(forResource: filename, withExtension: nil, subdirectory: "WakeWords")
    }

    private func presentMissingAccessKeyAlert() {
        let alert = NSAlert()
        alert.messageText = "Porcupine Access Key Needed"
        alert.informativeText = """
        Voice Dictation uses Picovoice Porcupine for wake-word detection, \
        which requires a free AccessKey from https://console.picovoice.ai/.

        Add it to \(AppConfig.configFileURL.path) as "porcupineAccessKey", \
        then relaunch the app.
        """
        alert.addButton(withTitle: "Open Config File")
        alert.addButton(withTitle: "OK")
        if alert.runModal() == .alertFirstButtonReturn {
            NSWorkspace.shared.open(AppConfig.configFileURL)
        }
    }
}

// MARK: - WakeWordEngineDelegate

extension AppDelegate: WakeWordEngineDelegate {
    func wakeWordEngine(_ engine: WakeWordEngine, didDetect event: WakeWordEvent) {
        switch event {
        case .wakePhraseDetected:
            beginDictation()
        case .stopPhraseDetected:
            AppLog.info("Stop phrase heard; sleeping")
            wakeWordEngine.stop()
            stateController.sleep()
        }
    }

    func wakeWordEngine(_ engine: WakeWordEngine, didFailWith error: Error) {
        AppLog.error("Wake word engine error: \(error)")
    }

    private func beginDictation() {
        do {
            try TextInjector.verifyReadyToType()
        } catch {
            AppLog.info("Wake word heard but not ready to type (\(error)); ignoring")
            return
        }

        stateController.beginActiveDictation()
        wakeWordEngine.stop() // free the mic for DictationRecorder

        dictationRecorder.record(
            silenceTimeout: config.silenceTimeoutSeconds,
            maxDuration: config.maxUtteranceSeconds
        ) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let audioURL):
                self.transcriber.transcribe(audioURL: audioURL) { transcriptionResult in
                    DispatchQueue.main.async {
                        self.handleTranscription(transcriptionResult)
                    }
                }
            case .failure(let error):
                AppLog.error("Dictation capture failed: \(error)")
                DispatchQueue.main.async { self.finishDictation() }
            }
        }
    }

    private func handleTranscription(_ result: Result<String, Error>) {
        switch result {
        case .success(let text) where !text.isEmpty:
            do {
                try TextInjector.insert(text: text)
                AppLog.info("Inserted dictated text (\(text.count) chars)")
            } catch {
                AppLog.error("Failed to insert text into Chrome: \(error)")
                statusItemController.showTranscriptionError("Couldn't insert text: \(error)")
            }
        case .success:
            AppLog.info("Transcription was empty; nothing inserted")
        case .failure(let error):
            AppLog.error("Transcription failed: \(error)")
            statusItemController.showTranscriptionError("Transcription failed")
        }
        finishDictation()
    }

    private func finishDictation() {
        stateController.endActiveDictation()
        wakeWordEngine.start()
    }
}

// MARK: - AppStateObserver

extension AppDelegate: AppStateObserver {
    func appState(_ state: AppStateController, didChangeTo newState: DictationState) {
        if newState == .asleep {
            wakeWordEngine.stop()
        }
    }
}

// MARK: - StatusItemControllerDelegate

extension AppDelegate: StatusItemControllerDelegate {
    func statusItemDidRequestPause(_ controller: StatusItemController) {
        wakeWordEngine.stop()
        stateController.sleep()
    }

    func statusItemDidRequestResume(_ controller: StatusItemController) {
        startUp()
    }

    func statusItemDidRequestQuit(_ controller: StatusItemController) {
        wakeWordEngine.teardown()
        NSApp.terminate(nil)
    }

    func statusItemDidRequestOpenConfig(_ controller: StatusItemController) {
        NSWorkspace.shared.open(AppConfig.configFileURL)
    }
}
