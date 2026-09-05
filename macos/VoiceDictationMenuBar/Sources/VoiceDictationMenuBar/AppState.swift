import Foundation

/// The three states shown in the menu bar icon and used to gate what the
/// audio pipeline is doing at any moment.
enum DictationState: Equatable {
    /// Wake word engine is off. Only a manual Resume (or relaunch) exits this.
    case asleep
    /// Wake word engine is running, waiting to hear the wake or stop phrase.
    case listening
    /// Wake phrase was heard; actively recording/transcribing an utterance.
    case active
}

protocol AppStateObserver: AnyObject {
    func appState(_ state: AppStateController, didChangeTo newState: DictationState)
}

/// Owns the current `DictationState` and the auto-sleep idle timer described
/// in the spec: after `autoSleepMinutes` with no wake-word activity, the app
/// drops from `.listening` to `.asleep` to save battery/CPU. Any wake-word
/// detection or manual Resume resets the idle clock.
final class AppStateController {
    private(set) var state: DictationState = .asleep {
        didSet {
            guard oldValue != state else { return }
            AppLog.info("State changed: \(oldValue) -> \(state)")
            for observer in observers {
                observer.appState(self, didChangeTo: state)
            }
        }
    }

    private var observers: [AppStateObserver] = []
    private var idleTimer: Timer?
    private var autoSleepInterval: TimeInterval

    init(autoSleepMinutes: Double) {
        self.autoSleepInterval = autoSleepMinutes * 60
    }

    func addObserver(_ observer: AppStateObserver) {
        observers.append(observer)
    }

    func updateAutoSleepMinutes(_ minutes: Double) {
        autoSleepInterval = minutes * 60
        if state == .listening {
            armIdleTimer()
        }
    }

    // MARK: - Transitions

    func startListening() {
        state = .listening
        armIdleTimer()
    }

    func beginActiveDictation() {
        idleTimer?.invalidate()
        state = .active
    }

    func endActiveDictation() {
        state = .listening
        armIdleTimer()
    }

    /// Wake-word / stop-word engine reported it heard *something* relevant
    /// (even the stop phrase counts, since that's still user activity).
    func noteActivity() {
        if state == .listening {
            armIdleTimer()
        }
    }

    func sleep() {
        idleTimer?.invalidate()
        idleTimer = nil
        state = .asleep
    }

    // MARK: - Idle timer

    private func armIdleTimer() {
        idleTimer?.invalidate()
        guard autoSleepInterval > 0 else { return }
        idleTimer = Timer.scheduledTimer(withTimeInterval: autoSleepInterval, repeats: false) { [weak self] _ in
            guard let self, self.state == .listening else { return }
            AppLog.info("Auto-sleeping after \(self.autoSleepInterval / 60) minutes of inactivity")
            self.sleep()
        }
        if let idleTimer {
            RunLoop.main.add(idleTimer, forMode: .common)
        }
    }
}
