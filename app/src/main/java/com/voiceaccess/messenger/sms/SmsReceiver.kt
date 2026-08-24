package com.voiceaccess.messenger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.voiceaccess.messenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SMS's equivalent of [com.voiceaccess.messenger.notification.MessageNotificationListenerService]:
 * the only entry point that queues SMS messages into the shared
 * `message_queue` table, tagged [com.voiceaccess.messenger.data.SourceApp.SMS].
 *
 * Unlike Outlook/WhatsApp, SMS never goes through a notification — the
 * platform delivers it directly as the `SMS_RECEIVED` broadcast, which this
 * app can observe (via RECEIVE_SMS) without being the default SMS app. Being
 * the default SMS app is a much heavier commitment (implementing the full
 * default-app contract: compose, `SMS_DELIVER`, quick-response, etc.) and is
 * deliberately out of scope — see [com.voiceaccess.messenger.sms.SmsReadMarker]
 * for what that tradeoff costs when it comes to marking a message read on
 * the platform side.
 *
 * Requires the user to grant RECEIVE_SMS/READ_SMS at runtime; see
 * MainActivity's permission flow. Without those permissions this receiver
 * is simply never invoked — nothing else in the app degrades.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "onReceive: SMS_RECEIVED with no messages in the intent")
            return
        }

        // A single SMS can arrive as several PDUs (long messages get split);
        // they all share the same originating address, so concatenate their
        // bodies into one logical message rather than queuing one row per PDU.
        val sender = messages.first().originatingAddress
        if (sender.isNullOrBlank()) {
            Log.w(TAG, "onReceive: SMS with no originating address, dropping it")
            return
        }
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val timestamp = messages.first().timestampMillis

        // onReceive must return quickly; goAsync() extends the receiver's
        // lifetime just long enough for this coroutine (DB write + thread-id
        // lookup) to finish, mirroring how the notification listener defers
        // its own Room write to a background dispatcher.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val threadId = resolveThreadId(appContext, sender)
                Log.d(TAG, "onReceive: queuing SMS from \"$sender\" (threadId=$threadId)")
                MessageRepository.getInstance(appContext).enqueueSms(
                    sender = sender,
                    previewText = body,
                    timestamp = timestamp,
                    phoneNumber = sender,
                    threadId = threadId,
                )
            } catch (e: Exception) {
                Log.e(TAG, "onReceive: failed to queue SMS from \"$sender\"", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * [Telephony.Threads.getOrCreateThreadId] *writes* to the platform
     * SMS/MMS provider (it creates the thread row if none exists yet), and
     * on several devices/OS versions that write is rejected for any app
     * that isn't the default SMS handler — same restriction as
     * [SmsReadMarker], but hit here on the *capture* path instead, where a
     * thrown SecurityException used to take the whole message down with it
     * (caught by the broad try/catch above, so the SMS was silently never
     * queued at all). Capturing a message must never depend on that write:
     * on failure, fall back to a synthetic id derived from the sender's
     * number, stable enough to group that sender's messages together. A
     * synthetic id just means [SmsReadMarker]'s later mark-as-read attempt
     * finds zero matching platform rows (a documented no-op already), not a
     * missing message.
     */
    private fun resolveThreadId(context: Context, sender: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, sender)
    } catch (e: SecurityException) {
        val fallback = sender.hashCode().toLong()
        Log.w(TAG, "resolveThreadId: getOrCreateThreadId denied for \"$sender\" (not the default SMS app) — " +
            "using a synthetic thread id ($fallback) so the message is still captured", e)
        fallback
    }

    private companion object {
        private const val TAG = "VAM-SmsReceiver"
    }
}
