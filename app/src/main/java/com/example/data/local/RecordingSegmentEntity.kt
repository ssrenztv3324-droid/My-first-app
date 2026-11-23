package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SegmentStatus {
    RECORDING,
    PENDING_UPLOAD,
    UPLOADING,
    UPLOADED_AND_DELETED,
    UPLOADED_KEPT,
    FAILED
}

@Entity(tableName = "recording_segments")
data class RecordingSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val localFilePath: String,
    val triggerMode: String, // "CONTINUOUS" or "MOTION_DETECT"
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val durationMillis: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val status: SegmentStatus = SegmentStatus.RECORDING,
    val uploadProgress: Int = 0, // 0..100
    val driveFileId: String? = null,
    val localDeleted: Boolean = false,
    val errorMessage: String? = null
)
