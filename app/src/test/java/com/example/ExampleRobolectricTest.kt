package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RecordingSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.StorageStatsProvider
import com.example.data.local.VaultDatabase
import com.example.service.MotionDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: VaultDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `app name matches VaultRec`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("VaultRec", appName)
    }

    @Test
    fun `segment lifecycle records upload and local deletion`() = runBlocking {
        val dao = db.segmentDao()
        val now = 1_700_000_000_000L
        val id = dao.insert(
            RecordingSegmentEntity(
                fileName = "VAULT_2H_test.mp4",
                localFilePath = "/tmp/VAULT_2H_test.mp4",
                triggerMode = "CONTINUOUS",
                startedAtMillis = now,
                status = SegmentStatus.RECORDING
            )
        )

        val twoHoursMs = 2L * 60L * 60L * 1000L
        val sizeBytes = 720L * 1024L * 1024L
        dao.finalizeRecording(
            id = id,
            endedAt = now + twoHoursMs,
            durationMillis = twoHoursMs,
            fileSizeBytes = sizeBytes,
            status = SegmentStatus.PENDING_UPLOAD
        )

        val pending = dao.getPendingSegments()
        assertEquals(1, pending.size)
        assertEquals(SegmentStatus.PENDING_UPLOAD, pending.first().status)

        dao.markUploadComplete(
            id = id,
            status = SegmentStatus.UPLOADED_AND_DELETED,
            driveFileId = "drive_abc123",
            localDeleted = true
        )

        val purgedTotal = dao.observeTotalPurgedBytes().first()
        assertEquals(sizeBytes, purgedTotal)
        assertEquals(1, dao.observeUploadedCount().first())
    }

    @Test
    fun `motion sensitivity thresholds decrease with higher sensitivity`() {
        val low = MotionDetector.thresholdForSensitivity(1)
        val med = MotionDetector.thresholdForSensitivity(2)
        val high = MotionDetector.thresholdForSensitivity(3)
        assertTrue(low > med && med > high)
    }

    @Test
    fun `storage formatter formats MB and GB accurately`() {
        assertEquals("720.0 MB", StorageStatsProvider.formatBytes(720L * 1024L * 1024L))
        assertEquals("2.00 GB", StorageStatsProvider.formatBytes(2L * 1024L * 1024L * 1024L))
    }
}
