package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SegmentConverters {
    @TypeConverter
    fun fromStatus(status: SegmentStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): SegmentStatus =
        runCatching { SegmentStatus.valueOf(value) }.getOrDefault(SegmentStatus.FAILED)
}

@Database(
    entities = [RecordingSegmentEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(SegmentConverters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun segmentDao(): RecordingSegmentDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        fun getInstance(context: Context): VaultDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vaultrec_segments.db"
                ).build().also { instance = it }
            }
        }
    }
}
