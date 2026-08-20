package com.voiceaccess.messenger.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.util.Log

/**
 * Attempts to flip the read flag in the *platform* SMS provider — the
 * database backing the device's actual Messages app — not just this app's
 * own queue.
 *
 * **Known Android limitation:** writes to [Telephony.Sms.CONTENT_URI]
 * (including marking a message read) are only honored from the app
 * currently holding the default-SMS-app role (Settings > Apps > Default
 * apps > SMS app); every other app's writes are rejected outright, even
 * with READ_SMS/RECEIVE_SMS granted. Becoming the default SMS app requires
 * implementing the full default-app contract (composing, receiving via
 * `SMS_DELIVER` instead of `SMS_RECEIVED`, a quick-response UI, etc.), which
 * is a much bigger surface than this app's read-only/voice-control purpose
 * and is deliberately out of scope. So on a normal install, [tryMarkRead]
 * is expected to update zero rows or throw [SecurityException] — callers
 * must surface that as a known, reported limitation (see
 * controller/MessageReadSync.kt), never silently treat it as success.
 */
object SmsReadMarker {

    fun tryMarkRead(context: Context, threadId: Long?): Boolean {
        if (threadId == null) return false
        return try {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            val rows = context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
            Log.d(TAG, "tryMarkRead: threadId=$threadId updated $rows row(s)")
            rows > 0
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "tryMarkRead: threadId=$threadId denied by the platform SMS provider — this app is not " +
                    "the default SMS handler, so the write was refused. This is an expected Android " +
                    "limitation, not a bug; see this class's doc comment.",
                e,
            )
            false
        }
    }

    private const val TAG = "VAM-SmsReadMarker"
}
