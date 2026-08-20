package com.voiceaccess.messenger.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends an outgoing SMS reply, giving SMS the same voice-driven "reply" flow
 * Outlook/WhatsApp already have (see VoiceAssistantController.handleSpokenReply).
 *
 * Unlike [SmsReadMarker]'s mark-as-read write, sending an SMS is *not*
 * restricted to the default-SMS-app role — SEND_SMS is a normal dangerous
 * permission any app can request and use — so this doesn't share that
 * class's platform-imposed limitation.
 */
object SmsSender {

    fun send(context: Context, phoneNumber: String, body: String): Boolean {
        if (phoneNumber.isBlank() || body.isBlank()) return false
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            // divideMessage/sendMultipartTextMessage handles both a short,
            // single-segment reply and a long one that needs splitting across
            // multiple SMS parts — sendTextMessage alone silently truncates
            // anything past one segment (~160 chars for GSM-7 encoding).
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.d(TAG, "send: sent ${parts.size} part(s) to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "send: failed to send SMS to $phoneNumber", e)
            false
        }
    }

    private const val TAG = "VAM-SmsSender"
}
