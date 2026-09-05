import AppKit

protocol StatusItemControllerDelegate: AnyObject {
    func statusItemDidRequestPause(_ controller: StatusItemController)
    func statusItemDidRequestResume(_ controller: StatusItemController)
    func statusItemDidRequestQuit(_ controller: StatusItemController)
    func statusItemDidRequestOpenConfig(_ controller: StatusItemController)
}

/// The menu bar icon + dropdown. Icon reflects `DictationState` at a glance;
/// the menu exposes the manual Pause/Resume/Quit controls the spec calls for.
final class StatusItemController: AppStateObserver {
    weak var delegate: StatusItemControllerDelegate?

    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let pauseResumeItem = NSMenuItem()
    private var lastError: String?

    init() {
        statusItem.button?.image = Self.icon(for: .asleep)
        statusItem.button?.toolTip = "Voice Dictation — Asleep"
        buildMenu()
    }

    private func buildMenu() {
        let menu = NSMenu()

        let statusLabel = NSMenuItem(title: "Status: Asleep", action: nil, keyEquivalent: "")
        statusLabel.isEnabled = false
        menu.addItem(statusLabel)
        menu.addItem(.separator())

        pauseResumeItem.title = "Resume"
        pauseResumeItem.target = self
        pauseResumeItem.action = #selector(pauseResumeTapped)
        menu.addItem(pauseResumeItem)

        let configItem = NSMenuItem(title: "Open Config File…", action: #selector(openConfigTapped), keyEquivalent: "")
        configItem.target = self
        menu.addItem(configItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "Quit Voice Dictation", action: #selector(quitTapped), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
        self.statusMenu = menu
    }

    private weak var statusMenu: NSMenu?

    // MARK: - AppStateObserver

    func appState(_ state: AppStateController, didChangeTo newState: DictationState) {
        statusItem.button?.image = Self.icon(for: newState)
        statusItem.button?.toolTip = "Voice Dictation — \(Self.label(for: newState))"

        if let statusLabel = statusMenu?.items.first {
            statusLabel.title = "Status: \(Self.label(for: newState))"
        }
        pauseResumeItem.title = newState == .asleep ? "Resume" : "Pause"
    }

    func showTranscriptionError(_ message: String) {
        lastError = message
        statusItem.button?.toolTip = "Voice Dictation — \(message)"
    }

    // MARK: - Actions

    @objc private func pauseResumeTapped() {
        if pauseResumeItem.title == "Resume" {
            delegate?.statusItemDidRequestResume(self)
        } else {
            delegate?.statusItemDidRequestPause(self)
        }
    }

    @objc private func openConfigTapped() {
        delegate?.statusItemDidRequestOpenConfig(self)
    }

    @objc private func quitTapped() {
        delegate?.statusItemDidRequestQuit(self)
    }

    // MARK: - Icon/label mapping

    private static func icon(for state: DictationState) -> NSImage? {
        let symbolName: String
        switch state {
        case .asleep: symbolName = "moon.zzz"
        case .listening: symbolName = "ear"
        case .active: symbolName = "waveform.circle.fill"
        }
        let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: label(for: state))
        image?.isTemplate = (state != .active)
        return image
    }

    private static func label(for state: DictationState) -> String {
        switch state {
        case .asleep: return "Asleep"
        case .listening: return "Listening"
        case .active: return "Active"
        }
    }
}
