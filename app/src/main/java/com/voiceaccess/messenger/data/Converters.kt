package com.voiceaccess.messenger.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromSourceApp(value: SourceApp): String = value.name

    @TypeConverter
    fun toSourceApp(value: String): SourceApp = SourceApp.valueOf(value)
}
