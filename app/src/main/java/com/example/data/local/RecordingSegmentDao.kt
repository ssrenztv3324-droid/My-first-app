package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingSegmentDao {
    @Query("SELECT * FROM recording_segments ORDER BY startedAtMillis DESC")
    fun observeAllSegments(): Flow<List<RecordingSegmentEntity>>

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM recording_segments WHERE localDeleted = 1")
    fun observeTotalPurgedBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM recording_segments WHERE status = 'UPLOADED_AND_DELETED'")
    fun observeUploadedCount(): Flow<Int>

    @Query("SELECT * FROM recording_segments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecordingSegmentEntity?

    @Query("SELECT * FROM recording_segments WHERE status IN ('PENDING_UPLOAD', 'FAILED') ORDER BY startedAtMillis ASC")
    suspend fun getPendingSegments(): List<RecordingSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: RecordingSegmentEntity): Long

    @Query(
        "UPDATE recording_segments SET endedAtMillis = :endedAt, durationMillis = :durationMillis, " +
            "fileSizeBytes = :fileSizeBytes, status = :status WHERE id = :id"
    )
    suspend fun finalizeRecording(
        id: Long,
        endedAt: Long,
        durationMillis: Long,
        fileSizeBytes: Long,
        status: SegmentStatus
    )

    @Query("UPDATE recording_segments SET status = :status, uploadProgress = :progress, errorMessage = :error WHERE id = :id")
    suspend fun updateUploadState(
        id: Long,
        status: SegmentStatus,
        progress: Int,
        error: String?
    )

    @Query(
        "UPDATE recording_segments SET status = :status, uploadProgress = 100, driveFileId = :driveFileId, " +
            "localDeleted = :localDeleted, errorMessage = NULL WHERE id = :id"
    )
    suspend fun markUploadComplete(
        id: Long,
        status: SegmentStatus,
        driveFileId: String,
        localDeleted: Boolean
    )

    @Query("UPDATE recording_segments SET localDeleted = 1, status = 'UPLOADED_AND_DELETED' WHERE id = :id")
    suspend fun markLocalPurged(id: Long)

    @Query("DELETE FROM recording_segments WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM recording_segments WHERE status = 'UPLOADED_AND_DELETED'")
    suspend fun clearCompletedLogs()
}
