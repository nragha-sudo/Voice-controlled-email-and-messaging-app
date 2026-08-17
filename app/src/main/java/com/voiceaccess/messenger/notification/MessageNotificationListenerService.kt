package com.voiceaccess.messenger.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Stage 1 of the pipeline: watches the notification shade for Outlook and
 * WhatsApp and queues a lightweight record of every message as it arrives.
 *
 * This service intentionally does the least amount of work possible — it
 * never tries to read the *full* message body, because notification text is
 * frequently truncated by the OS/app (long emails, long chat messages,
 * grouped "3 new messages" summaries). Full content is fetched on demand by
 * [com.voiceaccess.messenger.accessibility.MessageAccessibilityService] when
 * the user actually asks to hear a message.
 *
 * Requires the user to grant "Notification access" for this app in system
 * settings; see MainActivity for the permission check/launch flow.
 */
class MessageNotificationListenerService : NotificationListenerService() {

    private lateinit var repository: MessageRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = MessageRepository.getInstance(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // SourceApp.fromPackageName only matches the official Outlook package
        // ("com.microsoft.office.outlook") and WhatsApp's two packages — Gmail
        // ("com.google.android.gm") and everything else falls through to null
        // and is dropped right here, before it ever reaches Room. Logged so
        // this is verifiable via `adb logcat -s VAM-NotificationListener`
        // instead of just asserted.
        val sourceApp = SourceApp.fromPackageName(sbn.packageName)
        if (sourceApp == null) {
            Log.d(TAG, "Ignoring notification from ${sbn.packageName} (not Outlook/WhatsApp)")
            return
        }

        // Group summary notifications ("3 new messages") duplicate the
        // individual message notifications that also get posted; skip them.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.d(TAG, "Ignoring group summary notification from ${sbn.packageName}")
            return
        }

        val (sender, previewText) = extractSenderAndText(sbn.notification) ?: run {
            Log.w(TAG, "Could not extract sender/text from ${sourceApp.displayName} notification, dropping it")
            return
        }
        val timestamp = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime

        Log.d(TAG, "Queuing ${sourceApp.displayName} message from \"$sender\" (key=${sbn.key})")
        serviceScope.launch {
            repository.enqueue(
                sourceApp = sourceApp,
                sender = sender,
                previewText = previewText,
                timestamp = timestamp,
                notificationKey = sbn.key,
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op: the queue row must survive even if the OS notification is
        // dismissed (e.g. by the app itself, or the shade auto-clearing) —
        // dismissal is not the same as having been read aloud to the user.
    }

    /**
     * Prefers Android's structured MessagingStyle (used by both Outlook's
     * mail notifications and WhatsApp's chat notifications) over the raw
     * title/text extras, since MessagingStyle gives us the actual sender of
     * the latest message rather than a possibly-generic notification title.
     */
    private fun extractSenderAndText(notification: Notification): Pair<String, String>? {
        val messagingStyle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val lastMessage = messagingStyle?.messages?.lastOrNull()

        val extras = notification.extras
        if (lastMessage != null) {
            val sender = lastMessage.person?.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: return null
            val text = lastMessage.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return sender to text
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() } ?: return null
        return title to text
    }

    private companion object {
        private const val TAG = "VAM-NotificationListener"
    }
}
