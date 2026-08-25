package com.voiceaccess.messenger.controller

import android.content.Context
import android.util.Log
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.data.MessageEntity
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
import com.voiceaccess.messenger.sms.SmsReadMarker

/** Outcome of a [MessageReadSync.markRead] call, surfaced verbatim in the API's `/read` response. */
data class ReadSyncResult(val readOnSource: Boolean, val note: String?)

/**
 * Marks a message read in the local queue *and*, for SMS/WhatsApp, attempts
 * to mark it read on the actual source app too — the second half of
 * requirement 5. Outlook is intentionally excluded: it has no supported
 * mark-as-read path back from this app (no public API, and no on-screen
 * "mark read" affordance the accessibility service could safely drive
 * without also risking an accidental open/action on the wrong email).
 *
 * Used from three call sites that all end a message's life cycle the same
 * way: [com.voiceaccess.messenger.controller.VoiceAssistantController]'s
 * "done"/successful-reply actions (voice *or* button), and
 * `server/LocalApiServer`'s `POST /api/messages/{id}/read` (Claude/API flow)
 * — so "read via voice/button" and "read via Claude" mark the source app
 * identically instead of the API being a second, divergent code path.
 */
class MessageReadSync(private val context: Context) {

    /**
     * API-server path: marks read locally (keeps the row, flips [MessageEntity.readAloud])
     * and attempts the on-source mark. [whatsAppAlreadyOpened] lets a caller
     * that just opened the real WhatsApp conversation skip reopening it
     * here — see [markWhatsAppRead].
     */
    suspend fun markRead(
        repository: MessageRepository,
        message: MessageEntity,
        whatsAppAlreadyOpened: Boolean = false,
    ): ReadSyncResult {
        repository.markReadAloud(message.id)
        val result = attemptSourceMark(message, whatsAppAlreadyOpened)
        if (result.readOnSource) repository.markReadOnSource(message.id)
        Log.d(TAG, "markRead: id=${message.id} source=${message.sourceApp.name} readOnSource=${result.readOnSource}")
        return result
    }

    /**
     * Voice/button "Done" path, and a successfully sent "Reply": per the
     * user's spec, Done removes the message from the local database outright
     * rather than just flagging it read — unlike [markRead] there's no row
     * left afterward to flip [MessageEntity.readOnSource] on, but the
     * on-source mark-as-read attempt still happens first, exactly as before.
     */
    suspend fun markReadAndDelete(
        repository: MessageRepository,
        message: MessageEntity,
        whatsAppAlreadyOpened: Boolean = false,
    ): ReadSyncResult {
        val result = attemptSourceMark(message, whatsAppAlreadyOpened)
        repository.deleteMessage(message.id)
        Log.d(TAG, "markReadAndDelete: id=${message.id} source=${message.sourceApp.name} readOnSource=${result.readOnSource}")
        return result
    }

    private suspend fun attemptSourceMark(message: MessageEntity, whatsAppAlreadyOpened: Boolean): ReadSyncResult =
        when (message.sourceApp) {
            SourceApp.SMS -> markSmsRead(message)
            SourceApp.WHATSAPP -> markWhatsAppRead(message, whatsAppAlreadyOpened)
            SourceApp.OUTLOOK -> ReadSyncResult(
                readOnSource = false,
                note = "Outlook has no supported mark-as-read path from this app (no public API, and no " +
                    "safe on-screen affordance to drive) — skipped by design, only the local queue was updated.",
            )
        }

    private fun markSmsRead(message: MessageEntity): ReadSyncResult {
        val ok = SmsReadMarker.tryMarkRead(context, message.smsThreadId)
        return if (ok) {
            ReadSyncResult(readOnSource = true, note = null)
        } else {
            ReadSyncResult(
                readOnSource = false,
                note = "Could not mark read in the device's SMS provider. Android restricts that write to " +
                    "whichever app holds the default-SMS-app role, and this app does not request that role " +
                    "(it would require implementing the full default-SMS-app contract). Only the local queue " +
                    "was updated; the message will still show unread in the device's Messages app.",
            )
        }
    }

    /**
     * WhatsApp exposes no public mark-as-read API for third-party apps.
     * The only mechanism available is the same one requirement 4 already
     * relies on: actually opening the real conversation via the
     * accessibility service, which causes WhatsApp itself to flip its own
     * unread state (and send a read receipt, if enabled) because the chat
     * was genuinely displayed on screen — this is a real Android/WhatsApp
     * constraint, not a design shortcut.
     */
    private suspend fun markWhatsAppRead(message: MessageEntity, alreadyOpened: Boolean): ReadSyncResult {
        if (alreadyOpened) {
            return ReadSyncResult(
                readOnSource = true,
                note = "Marked via the WhatsApp conversation already opened earlier in this read flow.",
            )
        }

        val accessibility = MessageAccessibilityService.currentInstance()
            ?: return ReadSyncResult(
                readOnSource = false,
                note = "Accessibility service is not enabled on-device. WhatsApp has no public mark-as-read " +
                    "API, so this app can only mark a chat read by actually opening it on screen via the " +
                    "accessibility service — enable Settings > Accessibility > Voice Access Messenger to allow this.",
            )

        val opened = accessibility.openAndReadMessage(message) != null
        return if (opened) {
            ReadSyncResult(
                readOnSource = true,
                note = "Marked by opening the WhatsApp conversation via the accessibility service — WhatsApp " +
                    "itself updates its own read state once the chat is actually displayed.",
            )
        } else {
            ReadSyncResult(
                readOnSource = false,
                note = "Could not open the WhatsApp conversation to mark it read (app not foregroundable, " +
                    "conversation row not found, or the flow timed out). Only the local queue was updated.",
            )
        }
    }

    private companion object {
        private const val TAG = "VAM-ReadSync"
    }
}
