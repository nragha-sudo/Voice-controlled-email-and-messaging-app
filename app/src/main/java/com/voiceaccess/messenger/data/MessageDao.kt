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

    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 ORDER BY timestamp ASC")
    suspend fun getUnread(): List<MessageEntity>

    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 AND source_app = :sourceApp ORDER BY timestamp ASC")
    suspend fun getUnreadByApp(sourceApp: SourceApp): List<MessageEntity>

    @Query("SELECT * FROM message_queue WHERE read_aloud = 0 ORDER BY timestamp ASC")
    fun observeUnread(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM message_queue WHERE read_aloud = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM message_queue WHERE read_aloud = 0 AND source_app = :sourceApp")
    fun observeUnreadCountByApp(sourceApp: SourceApp): Flow<Int>

    @Query("UPDATE message_queue SET read_aloud = 1 WHERE id = :id")
    suspend fun markReadAloud(id: Long)

    @Query("DELETE FROM message_queue WHERE read_aloud = 1 AND timestamp < :olderThanTimestamp")
    suspend fun pruneReadOlderThan(olderThanTimestamp: Long)
}
