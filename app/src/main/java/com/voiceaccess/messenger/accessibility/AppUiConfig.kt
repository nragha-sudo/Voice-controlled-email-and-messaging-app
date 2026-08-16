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

    /** The reply / compose text field on the detail screen. */
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

    fun forApp(app: SourceApp): AppUiSelectors = when (app) {
        SourceApp.OUTLOOK -> outlook
        SourceApp.WHATSAPP -> whatsapp
    }
}
