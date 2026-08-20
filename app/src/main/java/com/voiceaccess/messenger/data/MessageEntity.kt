package com.voiceaccess.messenger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A row in the message_queue table. Populated by
 * [com.voiceaccess.messenger.notification.MessageNotificationListenerService]
 * the moment a notification arrives; [previewText] is whatever the
 * notification exposed and is frequently truncated, so it is only ever used
 * as a fallback — the accessibility service scrapes the real body on demand.
 */
@Entity(tableName = "message_queue")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "source_app")
    val sourceApp: SourceApp,

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "preview_text")
    val previewText: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "read_aloud", defaultValue = "0")
    val readAloud: Boolean = false,

    /**
     * De-duplication key: the platform NotificationListenerService key for
     * the posting notification (StatusBarNotification.key). Two
     * notifications from the same conversation before it's been read aloud
     * update the same row instead of queuing a duplicate. SMS rows have no
     * notification key (they arrive via broadcast, not NotificationListenerService)
     * and dedupe on [smsThreadId] + [timestamp] instead — see MessageRepository.
     */
    @ColumnInfo(name = "notification_key")
    val notificationKey: String? = null,

    /** SMS only: the sender's phone number, used to mark-as-read on the platform SMS provider and to deep-link into the Messages app. Null for Outlook/WhatsApp. */
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String? = null,

    /** SMS only: the platform SMS provider's conversation thread id (see [android.provider.Telephony.Threads]), used to mark that thread's messages read. Null for Outlook/WhatsApp. */
    @ColumnInfo(name = "sms_thread_id")
    val smsThreadId: Long? = null,

    /**
     * Whether this message has also been marked read on the *actual source
     * app* (SMS's platform provider, or WhatsApp via opening the real
     * conversation) — distinct from [readAloud], which only tracks our own
     * local queue. Always false for Outlook: there is no supported
     * mark-as-read path back to Outlook from this app. See
     * controller/MessageReadSync.kt.
     */
    @ColumnInfo(name = "read_on_source", defaultValue = "0")
    val readOnSource: Boolean = false,
)
