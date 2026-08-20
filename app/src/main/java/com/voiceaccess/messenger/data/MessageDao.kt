package com.voiceaccess.messenger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM message_queue WHERE notification_key = :notificationKey LIMIT 1")
    suspend fun findByNotificationKey(notificationKey: String): MessageEntity?

    // SMS rows have no notification_key (see MessageEntity), so incoming SMS
    // dedupes on thread + exact timestamp instead — the platform broadcast
    // doesn't re-post/update in place the way notifications do, but this
    // still guards against a duplicate broadcast delivery for the same message.
    @Query("SELECT * FROM message_queue WHERE source_app = 'SMS' AND sms_thread_id = :threadId AND timestamp = :timestamp LIMIT 1")
    suspend fun findSmsByThreadAndTimestamp(threadId: Long, timestamp: Long): MessageEntity?

    @Query("SELECT * FROM message_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    // Secondary "id ASC" tie-break makes the order fully deterministic even
    // when two rows share a timestamp (e.g. Outlook's `notification.when`
    // can be less precise than WhatsApp's, or several messages land in the
    // same millisecond) — without it, SQLite's tie order is unspecified and
    // can vary between runs, which is what "random order" looked like.
    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun getUnread(): List<MessageEntity>

    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 AND source_app = :sourceApp ORDER BY timestamp ASC, id ASC")
    suspend fun getUnreadByApp(sourceApp: SourceApp): List<MessageEntity>

    /** Most recent messages regardless of read state, for the API's "status=all" listing. */
    @Query("SELECT * FROM message_queue ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message_queue WHERE source_app = :sourceApp ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentByApp(sourceApp: SourceApp, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 ORDER BY timestamp ASC, id ASC")
    fun observeUnread(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message_queue WHERE read_aloud = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM message_queue WHERE read_aloud = 0 AND source_app = :sourceApp")
    fun observeUnreadCountByApp(sourceApp: SourceApp): Flow<Int>

    @Query("UPDATE message_queue SET read_aloud = 1 WHERE id = :id")
    suspend fun markReadAloud(id: Long)

    /** See [MessageEntity.readOnSource] — set once the *actual* SMS/WhatsApp app has also been marked read. */
    @Query("UPDATE message_queue SET read_on_source = 1 WHERE id = :id")
    suspend fun markReadOnSource(id: Long)

    @Query("DELETE FROM message_queue WHERE read_aloud = 1 AND timestamp < :olderThanTimestamp")
    suspend fun pruneReadOlderThan(olderThanTimestamp: Long)

    /** Wipes the whole queue (read and unread) — used by the "Clear Queue" debug button. */
    @Query("DELETE FROM message_queue")
    suspend fun clearAll()
}
