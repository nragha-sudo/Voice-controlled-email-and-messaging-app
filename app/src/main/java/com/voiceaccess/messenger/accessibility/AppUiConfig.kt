package com.voiceaccess.messenger.accessibility

import com.voiceaccess.messenger.data.SourceApp

/**
 * Hard-coded on-screen selectors for Outlook and WhatsApp.
 *
 * Outlook and WhatsApp do not publish their view resource IDs as a stable
 * API — these values were captured with Android Studio's Layout Inspector
 * against one build of each app and WILL need re-verifying after app
 * updates. Every field is a list of *candidates* tried in order, and
 * [NodeTreeUtils] falls back to text/content-description matching when none
 * of them hit, so a single ID rename doesn't necessarily break the flow —
 * but a real UI redesign will, and these lists are the one place to fix it.
 *
 * To refresh: enable Layout Inspector (View > Tool Windows > Layout
 * Inspector in Android Studio) while the target screen is open on a
 * connected device/emulator, and read the "view-id" off the component tree.
 */
data class AppUiSelectors(
    val app: SourceApp,

    /** Rows in the inbox / chat list that open a specific conversation. */
    val listItemContainerIds: List<String>,

    /** The toolbar search icon that opens the search UI. */
    val searchIconIds: List<String>,
    val searchIconContentDescriptions: List<String>,

    /** The text field that accepts search keywords once search UI is open. */
    val searchFieldIds: List<String>,

    /** Rows in the search results list, scraped for the spoken summary. */
    val searchResultItemIds: List<String>,

    /** Container(s) holding the full email/message body on the detail screen. */
    val messageBodyIds: List<String>,

    /**
     * Whether the body is one long scrollable document that must be
     * scrolled through and accumulated to capture the full content — true
     * for Outlook, whose email body only ever exposes whatever's currently
     * on screen. False for WhatsApp: a chat view is a scrollable list of
     * *many* messages, not one document, so scrolling it would read back
     * the entire conversation history instead of just the new message —
     * and its bubbles are short enough to already fit on screen anyway.
     */
    val scrollBodyForFullContent: Boolean,

    /**
     * Button that opens a reply/compose editor, for apps where it isn't
     * already on screen after opening a message (e.g. Outlook's reading
     * pane needs an explicit "Reply" tap; WhatsApp's input bar is already
     * inline, so its lists here are empty and the step is skipped).
     */
    val replyOpenButtonIds: List<String>,
    val replyOpenButtonContentDescriptions: List<String>,

    /** The reply / compose text field, once the editor above is open. */
    val replyFieldIds: List<String>,
    val replyFieldContentDescriptions: List<String>,

    /** The button that actually sends the reply. */
    val sendButtonIds: List<String>,
    val sendButtonContentDescriptions: List<String>,

    /**
     * Extra settle time (ms) after a window-change event fires, before we
     * trust the node tree enough to scrape it. Outlook renders message
     * bodies in a WebView, which finishes its accessibility tree noticeably
     * later than the window-state-changed event that fires when the
     * *activity* first appears; WhatsApp's native TextViews settle almost
     * immediately.
     */
    val settleDelayMs: Long,
)

object AppUiConfig {

    // --- Outlook (com.microsoft.office.outlook) -----------------------------
    // Verified against Outlook for Android, message-list build ~4.24xx.
    private val outlook = AppUiSelectors(
        app = SourceApp.OUTLOOK,
        listItemContainerIds = listOf(
            "com.microsoft.office.outlook:id/conversation_list_item",
            "com.microsoft.office.outlook:id/row_container",
        ),
        searchIconIds = listOf(
            "com.microsoft.office.outlook:id/search",
            "com.microsoft.office.outlook:id/menu_search",
        ),
        searchIconContentDescriptions = listOf("Search"),
        searchFieldIds = listOf(
            "com.microsoft.office.outlook:id/search_query_text",
            "com.microsoft.office.outlook:id/search_edit_text",
        ),
        searchResultItemIds = listOf(
            "com.microsoft.office.outlook:id/conversation_list_item",
        ),
        messageBodyIds = listOf(
            "com.microsoft.office.outlook:id/webview",
            "com.microsoft.office.outlook:id/message_body_webview",
            "com.microsoft.office.outlook:id/conversation_message_body",
        ),
        scrollBodyForFullContent = true,
        replyOpenButtonIds = listOf(
            "com.microsoft.office.outlook:id/reply_button",
            "com.microsoft.office.outlook:id/action_reply",
        ),
        replyOpenButtonContentDescriptions = listOf("Reply"),
        replyFieldIds = listOf(
            "com.microsoft.office.outlook:id/reply_compose_edit_text",
            "com.microsoft.office.outlook:id/compose_body_edit_text",
        ),
        replyFieldContentDescriptions = listOf("Message body", "Reply"),
        sendButtonIds = listOf(
            "com.microsoft.office.outlook:id/send_button",
            "com.microsoft.office.outlook:id/compose_send",
        ),
        sendButtonContentDescriptions = listOf("Send"),
        settleDelayMs = 600,
    )

    // --- WhatsApp (com.whatsapp) --------------------------------------------
    // WhatsApp's IDs are the most widely cross-referenced in the Android
    // automation/accessibility-tooling community and have stayed relatively
    // stable across versions, but still confirm against the installed build.
    private val whatsapp = AppUiSelectors(
        app = SourceApp.WHATSAPP,
        listItemContainerIds = listOf(
            "com.whatsapp:id/conversations_row_contact_name",
        ),
        searchIconIds = listOf(
            "com.whatsapp:id/menuitem_search",
        ),
        searchIconContentDescriptions = listOf("Search"),
        searchFieldIds = listOf(
            "com.whatsapp:id/search_src_text",
        ),
        searchResultItemIds = listOf(
            "com.whatsapp:id/conversations_row_contact_name",
        ),
        messageBodyIds = listOf(
            "com.whatsapp:id/conversation_text_row",
            "com.whatsapp:id/message_text",
        ),
        scrollBodyForFullContent = false,
        replyOpenButtonIds = emptyList(),
        replyOpenButtonContentDescriptions = emptyList(),
        replyFieldIds = listOf(
            "com.whatsapp:id/entry",
        ),
        replyFieldContentDescriptions = listOf("Type a message"),
        sendButtonIds = listOf(
            "com.whatsapp:id/send",
        ),
        sendButtonContentDescriptions = listOf("Send"),
        settleDelayMs = 200,
    )

    /**
     * SMS is never a valid argument here: it has no on-screen accessibility
     * flow to drive (it arrives with its full text already via broadcast —
     * see sms/SmsReceiver.kt — so there's nothing to scrape, and reply/
     * search for SMS are handled, or explicitly declined, before reaching
     * MessageAccessibilityService at all; see VoiceAssistantController and
     * server/LocalApiServer's SMS-specific branches).
     */
    fun forApp(app: SourceApp): AppUiSelectors = when (app) {
        SourceApp.OUTLOOK -> outlook
        SourceApp.WHATSAPP -> whatsapp
        SourceApp.SMS -> error("AppUiConfig has no selectors for SMS — SMS never goes through the accessibility flow, see this function's doc comment")
    }
}
