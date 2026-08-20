package com.voiceaccess.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_access_messenger.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        /**
         * Adds SMS source-tagging support: [MessageEntity.phoneNumber] and
         * [MessageEntity.smsThreadId] (both SMS-only, null for Outlook/
         * WhatsApp rows) plus [MessageEntity.readOnSource] (all sources) for
         * the new mark-as-read-on-source-app flow. Purely additive — every
         * existing Outlook/WhatsApp row is valid with the new columns
         * defaulted, so no data migration/backfill is needed.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_queue ADD COLUMN phone_number TEXT")
                db.execSQL("ALTER TABLE message_queue ADD COLUMN sms_thread_id INTEGER")
                db.execSQL("ALTER TABLE message_queue ADD COLUMN read_on_source INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
