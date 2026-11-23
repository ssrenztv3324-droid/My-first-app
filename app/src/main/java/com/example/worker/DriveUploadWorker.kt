package com.example.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.drive.GoogleDriveUploader
import com.example.data.local.SegmentStatus
import com.example.data.local.VaultDatabase
import com.example.data.local.VaultPreferences
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = VaultDatabase.getInstance(applicationContext)
        val dao = db.segmentDao()
        val prefs = VaultPreferences(applicationContext)
        val settings = prefs.settingsFlow.first()
        val authManager = GoogleDriveAuthManager(applicationContext)
        val uploader = GoogleDriveUploader()

        val targetId = inputData.getLong(KEY_SEGMENT_ID, -1L)
        val queue = if (targetId > 0L) {
            listOfNotNull(dao.getById(targetId))
        } else {
            dao.getPendingSegments()
        }

        if (queue.isEmpty()) return Result.success()

        val accessToken = authManager.getAccessTokenSilently()
        if (accessToken.isNullOrBlank()) {
            queue.forEach { seg ->
                dao.updateUploadState(
                    id = seg.id,
                    status = SegmentStatus.FAILED,
                    progress = 0,
                    error = "ยังไม่ได้เชื่อมต่อหรือสิทธิ์ Google Drive หมดอายุ กรุณากดเชื่อมต่อใหม่"
                )
            }
            return Result.failure()
        }

        var hadTransientFailure = false
        var cachedFolderId = settings.driveFolderId

        for (segment in queue) {
            val file = File(segment.localFilePath)
            if (!file.exists() || file.length() == 0L) {
                dao.updateUploadState(
                    id = segment.id,
                    status = SegmentStatus.FAILED,
                    progress = 0,
                    error = "ไม่พบไฟล์ในเครื่องแล้ว"
                )
                continue
            }

            dao.updateUploadState(
                id = segment.id,
                status = SegmentStatus.UPLOADING,
                progress = 1,
                error = null
            )

            var lastReportedPct = 0
            val uploadResult = uploader.uploadMp4(
                accessToken = accessToken,
                file = file,
                cachedFolderId = cachedFolderId
            ) { pct ->
                if (pct - lastReportedPct >= 5 || pct == 100) {
                    lastReportedPct = pct
                    // Best-effort non-blocking update inside worker coroutine not needed on every byte;
                    // handled via workData progress and final commit.
                    setProgressAsync(workDataOf(KEY_PROGRESS to pct, KEY_SEGMENT_ID to segment.id))
                }
            }

            uploadResult.fold(
                onSuccess = { uploaded ->
                    cachedFolderId = uploaded.folderId
                    prefs.setDriveFolderId(uploaded.folderId)

                    val deleted = if (settings.autoDeleteAfterUpload) {
                        runCatching { file.delete() }.getOrDefault(false)
                    } else {
                        false
                    }

                    val finalStatus = if (deleted) {
                        SegmentStatus.UPLOADED_AND_DELETED
                    } else {
                        SegmentStatus.UPLOADED_KEPT
                    }

                    dao.markUploadComplete(
                        id = segment.id,
                        status = finalStatus,
                        driveFileId = uploaded.fileId,
                        localDeleted = deleted
                    )
                },
                onFailure = { throwable ->
                    hadTransientFailure = true
                    dao.updateUploadState(
                        id = segment.id,
                        status = SegmentStatus.FAILED,
                        progress = 0,
                        error = throwable.localizedMessage ?: "อัปโหลดไม่สำเร็จ"
                    )
                }
            )
        }

        return if (hadTransientFailure) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_SEGMENT_ID = "segment_id"
        const val KEY_PROGRESS = "upload_progress"
        private const val UNIQUE_QUEUE_WORK = "vaultrec_drive_upload_queue"

        fun enqueueAllPending(context: Context, segmentId: Long = -1L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_SEGMENT_ID to segmentId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            val policy = if (segmentId > 0L) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_QUEUE_WORK, policy, request)
        }
    }
}
