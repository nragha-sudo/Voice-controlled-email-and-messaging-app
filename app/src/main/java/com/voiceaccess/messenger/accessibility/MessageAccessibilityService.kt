package com.voiceaccess.messenger.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
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
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            Log.w(TAG, "launchAndWaitForForeground: no launch intent for ${app.packageName} — is it installed?")
            return null
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        awaitedPackage = app.packageName
        pendingWindowSignal = CompletableDeferred()
        startActivity(launchIntent)

        val signaled = withTimeoutOrNull(COLD_START_TIMEOUT_MS) { pendingWindowSignal?.await() } != null
        awaitedPackage = null
        Log.d(TAG, "launchAndWaitForForeground(${app.name}): window-changed event received=$signaled")

        val root = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS)
        Log.d(TAG, "launchAndWaitForForeground(${app.name}): root found=${root != null}")
        return root
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

    /** Suspends until [packageName]'s window fires a state/content-changed event, or times out. Returns whether it signaled in time. */
    private suspend fun waitForWindowUpdate(packageName: String, timeoutMs: Long): Boolean {
        awaitedPackage = packageName
        pendingWindowSignal = CompletableDeferred()
        val signaled = withTimeoutOrNull(timeoutMs) { pendingWindowSignal?.await() } != null
        awaitedPackage = null
        return signaled
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
                ?.let {
                    Log.d(TAG, "findConversationRow(${app.name}): matched sender \"$sender\" via listItemContainerIds")
                    return it
                }
            return NodeTreeUtils.findFirstByTextContains(root, sender)?.also {
                Log.w(TAG, "findConversationRow(${app.name}): listItemContainerIds found ${candidateRows.size} rows " +
                    "but none matched \"$sender\" by exact row text — used the text-contains fallback instead. " +
                    "If this keeps happening, AppUiConfig.listItemContainerIds for ${app.name} is probably stale.")
            }
        }

        searchCurrentRoot()?.let { return it }

        Log.d(TAG, "findConversationRow(${app.name}): not found on current screen, trying back-navigation once")
        performGlobalAction(GLOBAL_ACTION_BACK)
        waitForWindowUpdate(app.packageName, BACK_NAV_TIMEOUT_MS)
        delay(selectors.settleDelayMs)

        val row = searchCurrentRoot()
        if (row == null) {
            Log.e(TAG, "findConversationRow(${app.name}): still not found for sender \"$sender\" after back-navigation retry")
        }
        return row
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
        Log.d(TAG, "openAndReadMessage: id=${message.id} app=${app.name} sender=\"${message.sender}\"")

        if (launchAndWaitForForeground(app) == null) {
            Log.e(TAG, "openAndReadMessage: id=${message.id} aborted — could not bring ${app.name} to the foreground")
            return@withLock null
        }

        val row = findConversationRow(app, message.sender, selectors)
        if (row == null) {
            Log.e(TAG, "openAndReadMessage: id=${message.id} aborted — no list row matched sender \"${message.sender}\"")
            return@withLock null
        }
        if (!NodeTreeUtils.click(row)) {
            Log.e(TAG, "openAndReadMessage: id=${message.id} aborted — matched row was not clickable")
            return@withLock null
        }

        val opened = waitForWindowUpdate(app.packageName, DETAIL_OPEN_TIMEOUT_MS)
        Log.d(TAG, "openAndReadMessage: id=${message.id} detail-screen window-changed event received=$opened")
        delay(selectors.settleDelayMs)

        val detailRoot = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS)
        if (detailRoot == null) {
            Log.e(TAG, "openAndReadMessage: id=${message.id} aborted — no root node for ${app.packageName} after opening")
            return@withLock null
        }

        val scraped = if (selectors.scrollBodyForFullContent) {
            scrapeBodyWithScrolling(message.id, app, selectors, detailRoot)
        } else {
            val bodyNode = NodeTreeUtils.findFirstByViewIdAny(detailRoot, selectors.messageBodyIds)
            if (bodyNode != null) {
                Log.d(TAG, "openAndReadMessage: id=${message.id} body container matched via messageBodyIds")
                NodeTreeUtils.collectVisibleText(bodyNode)
            } else {
                Log.w(TAG, "openAndReadMessage: id=${message.id} none of AppUiConfig's messageBodyIds " +
                    "(${selectors.messageBodyIds}) matched for ${app.name} — falling back to scraping the whole " +
                    "screen. This is the low-confidence path; if the read-out text includes toolbar/nav clutter, " +
                    "re-inspect the real body container's resource-id and add it to AppUiConfig.")
                NodeTreeUtils.collectVisibleText(detailRoot)
            }
        }

        Log.d(TAG, "openAndReadMessage: id=${message.id} scraped ${scraped.length} chars " +
            "(preview: \"${scraped.take(80)}${if (scraped.length > 80) "…" else ""}\")")

        scraped.takeIf { it.isNotBlank() }
    }

    /**
     * Scrolls through a long, single-document body (Outlook's email view)
     * and accumulates text across every scroll position — a single scrape
     * only ever sees whatever fits on screen, which is why long emails were
     * previously read back truncated. Stops once a scroll reveals no new
     * text (reached the bottom, or the body genuinely isn't scrollable) or
     * [MAX_SCROLL_ATTEMPTS] is hit, whichever comes first.
     */
    private suspend fun scrapeBodyWithScrolling(
        messageId: Long,
        app: SourceApp,
        selectors: AppUiSelectors,
        initialRoot: AccessibilityNodeInfo,
    ): String {
        val collected = LinkedHashSet<String>()
        var previousCount = -1
        var scrollSteps = 0

        while (scrollSteps < MAX_SCROLL_ATTEMPTS) {
            val root = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: initialRoot
            val scrapeRoot = NodeTreeUtils.findFirstByViewIdAny(root, selectors.messageBodyIds) ?: root
            collected.addAll(NodeTreeUtils.collectVisibleTextLines(scrapeRoot))

            if (collected.size == previousCount) {
                Log.d(TAG, "openAndReadMessage: id=$messageId scrolling stopped after $scrollSteps step(s) — " +
                    "no new text revealed (reached the end, or the body isn't actually scrollable)")
                break
            }
            previousCount = collected.size

            val scrollableNode = NodeTreeUtils.findScrollableNode(scrapeRoot)
            val scrolled = scrollableNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (!scrolled) {
                Log.d(TAG, "openAndReadMessage: id=$messageId no scrollable node found (or it refused to scroll " +
                    "further) after $scrollSteps step(s)")
                break
            }

            delay(selectors.settleDelayMs)
            scrollSteps++
        }

        Log.d(TAG, "openAndReadMessage: id=$messageId scraped body across $scrollSteps scroll step(s), " +
            "${collected.size} distinct line(s) total")
        return collected.joinToString("\n")
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
        Log.d(TAG, "performSearch: app=${app.name} keywords=\"$keywords\"")
        val listRoot = launchAndWaitForForeground(app) ?: return@withLock emptyList()

        val searchIcon = NodeTreeUtils.findFirstByViewIdAny(listRoot, selectors.searchIconIds)
            ?: NodeTreeUtils.findFirstByContentDescriptionAny(listRoot, selectors.searchIconContentDescriptions).also {
                if (it != null) Log.w(TAG, "performSearch: search icon found via content-description fallback, not searchIconIds")
            }
            ?: run {
                Log.e(TAG, "performSearch: aborted — no search icon found for ${app.name}")
                return@withLock emptyList()
            }
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
        val results = resultNodes
            .mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(MAX_SEARCH_RESULTS)
        Log.d(TAG, "performSearch: app=${app.name} found ${results.size} result(s) via searchResultItemIds " +
            "(${resultNodes.size} raw nodes matched before de-dup)")
        results
    }

    /**
     * Sends [replyText] into whichever conversation/email is currently on
     * screen — callers are expected to invoke this right after
     * [openAndReadMessage] while that conversation is still open, mirroring
     * the voice flow: hear a message, then optionally reply to it.
     */
    suspend fun sendReply(app: SourceApp, replyText: String): Boolean = actionMutex.withLock {
        val selectors = AppUiConfig.forApp(app)
        Log.d(TAG, "sendReply: app=${app.name} replyLength=${replyText.length}")
        var root = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: run {
            Log.e(TAG, "sendReply: aborted — no root node for ${app.packageName}")
            return@withLock false
        }

        val replyOpenButton = NodeTreeUtils.findFirstByViewIdAny(root, selectors.replyOpenButtonIds)
            ?: NodeTreeUtils.findFirstByContentDescriptionAny(root, selectors.replyOpenButtonContentDescriptions)
        if (replyOpenButton != null) {
            Log.d(TAG, "sendReply: opening compose editor via replyOpenButton for ${app.name}")
            NodeTreeUtils.click(replyOpenButton)
            waitForWindowUpdate(app.packageName, DETAIL_OPEN_TIMEOUT_MS)
            delay(selectors.settleDelayMs)
            root = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: root
        }

        val replyField = NodeTreeUtils.findFirstByViewIdAny(root, selectors.replyFieldIds)
            ?: NodeTreeUtils.findFirstByContentDescriptionAny(root, selectors.replyFieldContentDescriptions).also {
                if (it != null) Log.w(TAG, "sendReply: reply field found via content-description fallback, not replyFieldIds")
            }
            ?: run {
                Log.e(TAG, "sendReply: aborted — no reply field found for ${app.name}")
                return@withLock false
            }

        NodeTreeUtils.focus(replyField)
        NodeTreeUtils.click(replyField)
        if (!NodeTreeUtils.setText(replyField, replyText)) {
            Log.e(TAG, "sendReply: aborted — ACTION_SET_TEXT failed on reply field for ${app.name}")
            return@withLock false
        }

        // WhatsApp's send button is the same physical control that shows a
        // mic icon when the entry field is empty — it only becomes clickable
        // as "send" once the app's own TextWatcher reacts to the text we just
        // pushed via ACTION_SET_TEXT, which can lag a beat behind the action
        // returning true. A single fixed delay before looking for the send
        // button was the likely cause of replies intermittently missing
        // (clicking too early hits the button while it's still the mic), so
        // poll for the field to actually reflect the text instead of guessing
        // a delay.
        val textStuck = waitForReplyTextToStick(app.packageName, selectors, replyText)
        if (!textStuck) {
            Log.w(TAG, "sendReply: reply text did not visibly stick within ${TEXT_CONFIRM_TIMEOUT_MS}ms for ${app.name} — trying to send anyway")
        }

        delay(selectors.settleDelayMs)
        var clicked = false
        var sendButtonSeen = false
        repeat(SEND_CLICK_ATTEMPTS) { attempt ->
            if (clicked) return@repeat
            val refreshedRoot = pollForRoot(app.packageName, ROOT_POLL_TIMEOUT_MS) ?: root
            val sendButton = NodeTreeUtils.findFirstByViewIdAny(refreshedRoot, selectors.sendButtonIds)
                ?: NodeTreeUtils.findFirstByContentDescriptionAny(refreshedRoot, selectors.sendButtonContentDescriptions)
            if (sendButton != null) {
                sendButtonSeen = true
                clicked = NodeTreeUtils.click(sendButton)
            }
            Log.d(TAG, "sendReply: app=${app.name} send attempt=$attempt found=${sendButton != null} clicked=$clicked")
            if (!clicked) delay(SEND_RETRY_DELAY_MS)
        }

        if (!sendButtonSeen) {
            Log.e(TAG, "sendReply: aborted — no send button found for ${app.name}")
            return@withLock false
        }

        Log.d(TAG, "sendReply: app=${app.name} send button clicked=$clicked")
        clicked
    }

    /**
     * Polls the reply field until its live text matches [expected], instead
     * of trusting a single fixed delay — see the comment in [sendReply] for
     * why that single delay was flaky.
     */
    private suspend fun waitForReplyTextToStick(packageName: String, selectors: AppUiSelectors, expected: String): Boolean {
        val deadline = System.currentTimeMillis() + TEXT_CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val root = pollForRoot(packageName, ROOT_POLL_TIMEOUT_MS) ?: return false
            val field = NodeTreeUtils.findFirstByViewIdAny(root, selectors.replyFieldIds)
                ?: NodeTreeUtils.findFirstByContentDescriptionAny(root, selectors.replyFieldContentDescriptions)
            if (field?.text?.toString() == expected) return true
            delay(TEXT_CONFIRM_POLL_INTERVAL_MS)
        }
        return false
    }

    companion object {
        /** Filter logcat with `adb logcat -s VAM-Accessibility` to see exactly which selector/fallback path fires. */
        private const val TAG = "VAM-Accessibility"

        private const val MAX_SEARCH_RESULTS = 10
        private const val LIVE_FILTER_SETTLE_MS = 400L
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        /** How long to poll for the reply field to visibly reflect the text we just set, before giving up and trying to send anyway. */
        private const val TEXT_CONFIRM_TIMEOUT_MS = 1_500L
        private const val TEXT_CONFIRM_POLL_INTERVAL_MS = 100L

        /** A second send-button click attempt covers the case where the button was still showing its "mic" state on the first try. */
        private const val SEND_CLICK_ATTEMPTS = 2
        private const val SEND_RETRY_DELAY_MS = 350L

        /** Cap on scroll-and-accumulate steps for a long email body, so a mis-detected "scrollable" node can't loop forever. */
        private const val MAX_SCROLL_ATTEMPTS = 20

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
