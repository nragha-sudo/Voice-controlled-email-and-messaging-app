package com.voiceaccess.messenger.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceaccess.messenger.data.MessageEntity
import com.voiceaccess.messenger.data.SourceApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Stage 3 of the pipeline: everything notifications can't do — reading full
 * message bodies, searching, and replying — by driving Outlook/WhatsApp's
 * own UI through the Android accessibility APIs.
 *
 * All calls are serialized through [actionMutex] because only one
 * open-app/scrape/click sequence can sensibly run at a time (the device only
 * has one foreground window), and every public entry point assumes it has
 * exclusive control of the screen for its duration.
 *
 * Requires the user to enable this specific service under
 * Settings > Accessibility > Downloaded apps; see [isEnabled] for the check
 * MainActivity uses before offering voice commands.
 */
class MessageAccessibilityService : AccessibilityService() {

    private val actionMutex = Mutex()

    // Set while a suspend function is waiting for a specific app's window to
    // settle; onAccessibilityEvent completes it the moment a matching event
    // arrives, instead of the caller having to poll.
    @Volatile private var awaitedPackage: String? = null
    @Volatile private var pendingWindowSignal: CompletableDeferred<Unit>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)

        // Configured programmatically (in addition to the declarative
        // baseline in xml/accessibility_service_config.xml) so the watched
        // package list has one source of truth: SourceApp.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = arrayOf(
                SourceApp.OUTLOOK.packageName,
                SourceApp.WHATSAPP.packageName,
                WHATSAPP_BUSINESS_PACKAGE,
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        val isWindowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowEvent || eventPackage != awaitedPackage) return

        pendingWindowSignal?.let { if (!it.isCompleted) it.complete(Unit) }
    }

    override fun onInterrupt() {
        // Nothing to clean up: every suspend action already guards itself
        // with timeouts, so an OS-initiated interrupt just lets those
        // timeouts fire naturally.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Launches [app] to the foreground (or brings it forward if already
     * running) and suspends until its window is on screen and settled.
     * Returns the root node of that window, or null on timeout/failure.
     */
    private suspend fun launchAndWaitForForeground(app: SourceApp): AccessibilityNodeInfo? {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        awaitedPackage = app.packageName
        pendingWindowSignal = CompletableDeferred()
        startActivity(launchIntent)

        withTimeoutOrNull(COLD_START_TIMEOUT_MS) { pendingWindowSignal?.await() }
        awaitedPackage = null

        return pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS)
    }

    /** Polls [rootInActiveWindow] until it belongs to [packageName] or the timeout elapses. */
    private suspend fun pollForRoot(packageName: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == packageName) return root
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** Suspends until [packageName]'s window fires a state/content-changed event, or times out. */
    private suspend fun waitForWindowUpdate(packageName: String, timeoutMs: Long) {
        awaitedPackage = packageName
        pendingWindowSignal = CompletableDeferred()
        withTimeoutOrNull(timeoutMs) { pendingWindowSignal?.await() }
        awaitedPackage = null
    }

    /**
     * Finds the inbox/chat-list row for [sender], retrying once after a
     * back-navigation in case the app resumed mid-conversation rather than
     * on the list screen.
     */
    private suspend fun findConversationRow(
        app: SourceApp,
        sender: String,
        selectors: AppUiSelectors,
    ): AccessibilityNodeInfo? {
        fun searchCurrentRoot(): AccessibilityNodeInfo? {
            val root = rootInActiveWindow ?: return null
            val candidateRows = NodeTreeUtils.findAllByViewIdAny(root, selectors.listItemContainerIds)
            candidateRows.firstOrNull { row -> row.text?.toString()?.contains(sender, true) == true }
                ?.let { return it }
            return NodeTreeUtils.findFirstByTextContains(root, sender)
        }

        searchCurrentRoot()?.let { return it }

        performGlobalAction(GLOBAL_ACTION_BACK)
        waitForWindowUpdate(app.packageName, BACK_NAV_TIMEOUT_MS)
        delay(selectors.settleDelayMs)

        return searchCurrentRoot()
    }

    /**
     * Opens the conversation/email matching [message]'s sender and scrapes
     * its full visible body text — the whole reason this service exists,
     * since [message].previewText is only ever the (possibly truncated)
     * notification text.
     */
    suspend fun openAndReadMessage(message: MessageEntity): String? = actionMutex.withLock {
        val app = message.sourceApp
        val selectors = AppUiConfig.forApp(app)

        launchAndWaitForForeground(app) ?: return@withLock null

        val row = findConversationRow(app, message.sender, selectors) ?: return@withLock null
        if (!NodeTreeUtils.click(row)) return@withLock null

        waitForWindowUpdate(app.packageName, DETAIL_OPEN_TIMEOUT_MS)
        delay(selectors.settleDelayMs)

        val detailRoot = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: return@withLock null
        val bodyNode = NodeTreeUtils.findFirstByViewIdAny(detailRoot, selectors.messageBodyIds)
        val scraped = bodyNode?.let { NodeTreeUtils.collectVisibleText(it) }
            ?: NodeTreeUtils.collectVisibleText(detailRoot)

        scraped.takeIf { it.isNotBlank() }
    }

    /**
     * Opens [app], drives its search UI with [keywords], and scrapes the
     * resulting match list. Handles both interaction models found in the
     * wild: live-filter-as-you-type (WhatsApp) and explicit-submit search
     * (Outlook) — it waits briefly after typing for live results, then also
     * best-effort submits the IME search action in case the app needs it.
     */
    suspend fun performSearch(app: SourceApp, keywords: String): List<String> = actionMutex.withLock {
        val selectors = AppUiConfig.forApp(app)
        val listRoot = launchAndWaitForForeground(app) ?: return@withLock emptyList()

        val searchIcon = NodeTreeUtils.findFirstByViewIdAny(listRoot, selectors.searchIconIds)
            ?: NodeTreeUtils.findFirstByContentDescriptionAny(listRoot, selectors.searchIconContentDescriptions)
            ?: return@withLock emptyList()
        if (!NodeTreeUtils.click(searchIcon)) return@withLock emptyList()

        waitForWindowUpdate(app.packageName, DETAIL_OPEN_TIMEOUT_MS)
        delay(selectors.settleDelayMs)

        val searchScreenRoot = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: return@withLock emptyList()
        val searchField = NodeTreeUtils.findFirstByViewIdAny(searchScreenRoot, selectors.searchFieldIds)
            ?: return@withLock emptyList()

        NodeTreeUtils.focus(searchField)
        if (!NodeTreeUtils.setText(searchField, keywords)) return@withLock emptyList()

        // Give live-filtering UIs (WhatsApp) time to render, then best-effort
        // submit the IME search action for UIs that require it (Outlook).
        delay(LIVE_FILTER_SETTLE_MS)
        pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS)
            ?.let { root -> NodeTreeUtils.findFirstByViewIdAny(root, selectors.searchFieldIds) }
            ?.let { field -> NodeTreeUtils.submitImeAction(field) }

        waitForWindowUpdate(app.packageName, DETAIL_OPEN_TIMEOUT_MS)
        delay(selectors.settleDelayMs)

        val resultsRoot = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: return@withLock emptyList()
        val resultNodes = NodeTreeUtils.findAllByViewIdAny(resultsRoot, selectors.searchResultItemIds)
        resultNodes
            .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(MAX_SEARCH_RESULTS)
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 10
        private const val LIVE_FILTER_SETTLE_MS = 400L
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        private const val COLD_START_TIMEOUT_MS = 6_000L
        private const val ROOT_POLL_TIMEOUT_MS = 4_000L
        private const val DETAIL_OPEN_TIMEOUT_MS = 4_000L
        private const val BACK_NAV_TIMEOUT_MS = 2_000L
        private const val POLL_INTERVAL_MS = 100L

        @Volatile
        private var instance: WeakReference<MessageAccessibilityService>? = null

        fun currentInstance(): MessageAccessibilityService? = instance?.get()

        /** Whether the user has enabled this service under system Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(context, MessageAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
