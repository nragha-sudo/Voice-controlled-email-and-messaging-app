package com.voiceaccess.messenger.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper around [MessageDao] so both the notification listener
 * (writer) and the UI/voice controller (reader) share one entry point
 * instead of touching Room directly.
 */
class MessageRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).messageDao()

    fun observeUnread(): Flow<List<MessageEntity>> = dao.observeUnread()

    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    suspend fun getUnread(): List<MessageEntity> = dao.getUnread()

    suspend fun markReadAloud(id: Long) = dao.markReadAloud(id)

    /**
     * Inserts a freshly-posted notification, or updates the existing queue
     * row in place if we've already seen this notification key (e.g. the OS
     * re-posted an updated WhatsApp group summary before it was read aloud).
     */
    suspend fun enqueue(
        sourceApp: SourceApp,
        sender: String,
        previewText: String,
        timestamp: Long,
        notificationKey: String?,
    ) {
        val existing = notificationKey?.let { dao.findByNotificationKey(it) }
        val entity = MessageEntity(
            id = existing?.id ?: 0,
            sourceApp = sourceApp,
            sender = sender,
            previewText = previewText,
            timestamp = timestamp,
            readAloud = false,
            notificationKey = notificationKey,
        )
        dao.insert(entity)
    }

    companion object {
        @Volatile
        private var instance: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository =
            instance ?: synchronized(this) {
                instance ?: MessageRepository(context.applicationContext).also { instance = it }
            }
    }
}
