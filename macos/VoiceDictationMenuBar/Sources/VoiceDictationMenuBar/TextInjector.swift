import ApplicationServices
import AppKit
import Carbon.HIToolbox

enum TextInjectorError: Error {
    case accessibilityNotTrusted
    case chromeNotFrontmost
    case noFocusedElement
}

/// Types transcribed text into whatever text field currently has keyboard
/// focus inside Google Chrome — the Claude.ai message box in particular,
/// but this makes no Claude.ai-specific assumptions beyond "focused element
/// in the Chrome process".
///
/// Two things happen, deliberately in this order:
/// 1. The Accessibility API (`AXUIElement`) is used to find Chrome's
///    focused element and confirm it's actually text-editable, so we never
///    type into the wrong place.
/// 2. The text is delivered as synthetic keyboard events (`CGEvent`
///    Unicode key-down/up pairs), not by setting the AX value directly.
///    Most web text inputs — including Claude.ai's React-controlled
///    composer — only update their internal state in response to real
///    input events, so this is what "behaves exactly as if typed by hand"
///    requires in practice; a direct `kAXValueAttribute` write often gets
///    silently discarded or leaves the page's JS state out of sync.
enum TextInjector {
    static let chromeBundleIdentifier = "com.google.Chrome"

    /// Verifies Chrome is frontmost and has a focused, text-editable element,
    /// throwing a descriptive error otherwise. Callers should surface these
    /// as a quiet status-item state change rather than an alert, since it's
    /// an expected condition (the user hasn't clicked into Claude.ai's
    /// composer, or is in another app) rather than a bug.
    static func verifyReadyToType() throws {
        guard AXIsProcessTrusted() else { throw TextInjectorError.accessibilityNotTrusted }

        guard let frontApp = NSWorkspace.shared.frontmostApplication,
              frontApp.bundleIdentifier == chromeBundleIdentifier else {
            throw TextInjectorError.chromeNotFrontmost
        }

        guard focusedElement(pid: frontApp.processIdentifier) != nil else {
            throw TextInjectorError.noFocusedElement
        }
    }

    static func insert(text: String) throws {
        try verifyReadyToType()
        guard !text.isEmpty else { return }
        typeUnicodeString(text)
    }

    // MARK: - Accessibility lookups

    private static func focusedElement(pid: pid_t) -> AXUIElement? {
        let appElement = AXUIElementCreateApplication(pid)
        var focused: CFTypeRef?
        let result = AXUIElementCopyAttributeValue(appElement, kAXFocusedUIElementAttribute as CFString, &focused)
        guard result == .success, let focused else { return nil }
        return (focused as! AXUIElement)
    }

    // MARK: - Synthetic typing

    /// Posts each character of `text` as a Unicode key event pair at the
    /// HID event tap location, the same layer real keystrokes arrive on, so
    /// it works regardless of the active keyboard layout and needs no
    /// virtual-keycode mapping for non-ASCII characters.
    private static func typeUnicodeString(_ text: String) {
        // Chunk into UTF-16 code units per CGEvent's own limit (255 UniChar
        // per event); dictated sentences are normally far under this, but
        // don't silently truncate a long one.
        let utf16 = Array(text.utf16)
        let chunkSize = 20
        var index = 0
        while index < utf16.count {
            let end = min(index + chunkSize, utf16.count)
            let chunk = Array(utf16[index..<end])
            postUnicodeChunk(chunk)
            index = end
        }
    }

    private static func postUnicodeChunk(_ chunk: [UniChar]) {
        guard let source = CGEventSource(stateID: .hidSystemState) else { return }

        guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
              let keyUp = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else {
            return
        }

        chunk.withUnsafeBufferPointer { buffer in
            keyDown.keyboardSetUnicodeString(stringLength: buffer.count, unicodeString: buffer.baseAddress)
            keyUp.keyboardSetUnicodeString(stringLength: buffer.count, unicodeString: buffer.baseAddress)
        }

        keyDown.post(tap: .cghidEventTap)
        keyUp.post(tap: .cghidEventTap)
    }
}
