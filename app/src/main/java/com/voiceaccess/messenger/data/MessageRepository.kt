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

    /** Per-app unread badge count, e.g. for the Outlook/WhatsApp buttons; null means combined. */
    fun observeUnreadCount(app: SourceApp?): Flow<Int> =
        if (app == null) dao.observeUnreadCount() else dao.observeUnreadCountByApp(app)

    /** [app] null reads the combined queue (used by the generic "read my messages" voice phrase). */
    suspend fun getUnread(app: SourceApp? = null): List<MessageEntity> =
        if (app == null) dao.getUnread() else dao.getUnreadByApp(app)

    /** Most recent messages regardless of read state (the API's "status=all" listing); [app] null is combined. */
    suspend fun getRecent(app: SourceApp? = null, limit: Int = 100): List<MessageEntity> =
        if (app == null) dao.getRecent(limit) else dao.getRecentByApp(app, limit)

    suspend fun getById(id: Long): MessageEntity? = dao.getById(id)

    suspend fun markReadAloud(id: Long) = dao.markReadAloud(id)

    /** See [MessageEntity.readOnSource]. */
    suspend fun markReadOnSource(id: Long) = dao.markReadOnSource(id)

    /** Removes one message outright — used by the "Done" action, which deletes rather than just flags read. */
    suspend fun deleteMessage(id: Long) = dao.deleteById(id)

    /** Wipes the local queue. [app] null wipes everything (the yellow "Clear Cache" bar); otherwise just that source (the per-app buttons). New notifications are captured normally afterward either way. */
    suspend fun clearAll(app: SourceApp? = null) {
        if (app == null) dao.clearAll() else dao.clearAllByApp(app)
    }

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

    /**
     * Inserts an incoming SMS. Separate from [enqueue] because SMS has no
     * notification key to dedupe on (see [MessageDao.findSmsByThreadAndTimestamp])
     * and carries [phoneNumber]/[threadId] instead, used later to mark it
     * read on the platform SMS provider (see controller/MessageReadSync.kt).
     */
    suspend fun enqueueSms(
        sender: String,
        previewText: String,
        timestamp: Long,
        phoneNumber: String,
        threadId: Long,
    ) {
        val existing = dao.findSmsByThreadAndTimestamp(threadId, timestamp)
        if (existing != null) return
        dao.insert(
            MessageEntity(
                sourceApp = SourceApp.SMS,
                sender = sender,
                previewText = previewText,
                timestamp = timestamp,
                readAloud = false,
                phoneNumber = phoneNumber,
                smsThreadId = threadId,
            ),
        )
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
