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
     * update the same row instead of queuing a duplicate.
     */
    @ColumnInfo(name = "notification_key")
    val notificationKey: String? = null,
)
