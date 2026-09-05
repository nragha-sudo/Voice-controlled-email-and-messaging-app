import AVFoundation
import AppKit
import ApplicationServices

/// Requests and checks the two permissions this app cannot function without:
///
/// - **Microphone**: needed for both wake-word detection and dictation capture.
/// - **Accessibility**: needed to read the focused UI element in Chrome and
///   type the transcribed text into it.
///
/// Both are explained to the user with a plain-language alert *before* the
/// system prompt (for mic) or before sending them to System Settings (for
/// Accessibility, which has no in-app prompt), per the spec's requirement
/// that the app "clearly explain why it needs" each permission.
enum PermissionsManager {

    static func hasMicrophonePermission() -> Bool {
        AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
    }

    static func hasAccessibilityPermission() -> Bool {
        AXIsProcessTrusted()
    }

    /// Call once on first launch (or whenever a required permission is missing).
    /// Walks the user through both permissions in order, explaining each first.
    static func ensurePermissions(completion: @escaping (_ granted: Bool) -> Void) {
        requestMicrophone { micGranted in
            DispatchQueue.main.async {
                if !micGranted {
                    showBlockingAlert(
                        title: "Microphone Access Needed",
                        message: """
                        Voice Dictation can't hear the wake word or your dictation without \
                        microphone access. Open System Settings > Privacy & Security > \
                        Microphone and enable it for Voice Dictation, then relaunch the app.
                        """,
                        openSettingsPane: "Privacy_Microphone"
                    )
                }
                requestAccessibilityIfNeeded()
                completion(micGranted && hasAccessibilityPermission())
            }
        }
    }

    private static func requestMicrophone(completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            completion(true)
        case .notDetermined:
            let alert = NSAlert()
            alert.messageText = "Microphone Access"
            alert.informativeText = """
            Voice Dictation continuously listens on-device for the wake word \
            ("Hey Claude") and, once activated, for what you dictate. No audio \
            ever leaves your Mac — wake-word detection and transcription both \
            run locally. macOS will now ask you to confirm microphone access.
            """
            alert.addButton(withTitle: "Continue")
            alert.runModal()
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                completion(granted)
            }
        case .denied, .restricted:
            completion(false)
        @unknown default:
            completion(false)
        }
    }

    private static func requestAccessibilityIfNeeded() {
        guard !hasAccessibilityPermission() else { return }

        let alert = NSAlert()
        alert.messageText = "Accessibility Access"
        alert.informativeText = """
        To type your dictated text into Chrome's message box, Voice Dictation \
        needs Accessibility access. This lets it find the text field you're \
        focused on and insert text into it, exactly as if you'd typed it — it \
        does not let the app see or control anything else on your Mac.

        Click Open System Settings, then enable Voice Dictation in the list \
        and relaunch the app.
        """
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Later")
        let response = alert.runModal()

        if response == .alertFirstButtonReturn {
            // This also triggers the system's own Accessibility prompt/registration
            // if the app isn't listed yet.
            let options: NSDictionary = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true]
            _ = AXIsProcessTrustedWithOptions(options)
        }
    }

    private static func showBlockingAlert(title: String, message: String, openSettingsPane: String?) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Later")
        if alert.runModal() == .alertFirstButtonReturn, let pane = openSettingsPane,
           let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)") {
            NSWorkspace.shared.open(url)
        }
    }
}
