package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.R
import com.example.data.local.RecordingSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.StorageStatsProvider
import com.example.data.local.VaultDatabase
import com.example.data.local.VaultPreferences
import com.example.data.local.VaultSettings
import com.example.data.local.VideoQualityPreset
import com.example.worker.DriveUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CameraCapturePhase {
    IDLE,
    PREPARING,
    RECORDING,
    ERROR
}

data class CameraRuntimeState(
    val phase: CameraCapturePhase = CameraCapturePhase.IDLE,
    val useFrontCamera: Boolean = false,
    val activeSegmentFileName: String? = null,
    val activeSegmentBytes: Long = 0L,
    val segmentElapsedSeconds: Long = 0L,
    val cycleTargetSeconds: Long = 7200L,
    val completedCyclesCount: Int = 0,
    val lastError: String? = null
)

class CameraVaultService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.action.START_CAMERA_VAULT"
        const val ACTION_STOP = "com.example.action.STOP_CAMERA_VAULT"
        const val EXTRA_USE_FRONT_CAMERA = "extra_use_front_camera"

        private const val CHANNEL_ID = "vaultrec_silent_sync_v1"
        private const val NOTIFICATION_ID = 4092

        private val _runtimeState = MutableStateFlow(CameraRuntimeState())
        val runtimeState: StateFlow<CameraRuntimeState> = _runtimeState.asStateFlow()

        fun startIntent(context: Context, useFrontCamera: Boolean = false): Intent {
            return Intent(context, CameraVaultService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USE_FRONT_CAMERA, useFrontCamera)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, CameraVaultService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcherJob: Job? = null

    private var currentSettings = VaultSettings()
    private var useFrontCamera = false
    private var activeSegmentId: Long = -1L
    private var activeFile: File? = null
    private var segmentStartMillis: Long = 0L
    private var completedCycles = 0
    @Volatile
    private var isStopping = false
    @Volatile
    private var rotateOnFinalize = false

    override fun onCreate() {
        super.onCreate()
        createSilentNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCameraVaultGracefully(errorMessage = null)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    _runtimeState.value = CameraRuntimeState(
                        phase = CameraCapturePhase.ERROR,
                        lastError = "ยังไม่ได้อนุญาตสิทธิ์กล้อง (CAMERA)"
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                useFrontCamera = intent.getBooleanExtra(EXTRA_USE_FRONT_CAMERA, false)
                isStopping = false
                rotateOnFinalize = false
                completedCycles = 0

                startForegroundSilent("Sync Active")

                lifecycleScope.launch {
                    currentSettings = VaultPreferences(applicationContext).settingsFlow.first()
                    startForegroundSilent(currentSettings.stealthNotificationTitle)
                    bindCameraAndRecord()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSilent(title: String) {
        val notification = buildSilentNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(title: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_default_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun bindCameraAndRecord() {
        _runtimeState.value = CameraRuntimeState(
            phase = CameraCapturePhase.PREPARING,
            useFrontCamera = useFrontCamera,
            cycleTargetSeconds = currentSettings.cycleMinutes * 60L
        )
        acquireWakeLock()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val targetQuality = when (currentSettings.videoQuality) {
                        VideoQualityPreset.COMPACT_360P -> Quality.LOWEST
                        VideoQualityPreset.BALANCED_480P -> Quality.SD
                        VideoQualityPreset.SHARP_720P -> Quality.HD
                    }
                    val qualitySelector = QualitySelector.from(
                        targetQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )

                    val recorder = Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build()

                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc

                    val selector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, vc)

                    startNewCameraSegment()
                    startCycleWatcher()
                } catch (e: Exception) {
                    stopCameraVaultGracefully(
                        errorMessage = e.localizedMessage ?: "ไม่สามารถเปิดกล้อง CameraX ได้"
                    )
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun startNewCameraSegment() {
        val vc = videoCapture ?: return
        val dir = StorageStatsProvider.getRecordingsDir(applicationContext)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "VAULT_CAM_$stamp.mp4")

        activeFile = outFile
        segmentStartMillis = System.currentTimeMillis()
        rotateOnFinalize = false

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = VaultDatabase.getInstance(applicationContext).segmentDao()
            activeSegmentId = dao.insert(
                RecordingSegmentEntity(
                    fileName = outFile.name,
                    localFilePath = outFile.absolutePath,
                    triggerMode = "CAMERA_BACKGROUND",
                    startedAtMillis = segmentStartMillis,
                    status = SegmentStatus.RECORDING
                )
            )
        }

        val outputOptions = FileOutputOptions.Builder(outFile).build()
        val pendingRecording = vc.output.prepareRecording(this, outputOptions)

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _runtimeState.value = _runtimeState.value.copy(
                        phase = CameraCapturePhase.RECORDING,
                        activeSegmentFileName = outFile.name,
                        activeSegmentBytes = 0L,
                        segmentElapsedSeconds = 0L,
                        cycleTargetSeconds = currentSettings.cycleMinutes * 60L,
                        completedCyclesCount = completedCycles,
                        lastError = null
                    )
                }
                is VideoRecordEvent.Status -> {
                    val elapsedSec = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    val bytes = event.recordingStats.numBytesRecorded
                    _runtimeState.value = _runtimeState.value.copy(
                        activeSegmentBytes = bytes,
                        segmentElapsedSeconds = elapsedSec
                    )
                }
                is VideoRecordEvent.Finalize -> {
                    val finalizedId = activeSegmentId
                    val finalizedFile = activeFile
                    val startMs = segmentStartMillis
                    val shouldRotateNext = rotateOnFinalize && !isStopping
                    val err = if (event.hasError()) "CameraX error (${event.error})" else null

                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = VaultDatabase.getInstance(applicationContext).segmentDao()
                        if (finalizedId > 0L && finalizedFile != null && finalizedFile.exists() && finalizedFile.length() > 0L) {
                            val now = System.currentTimeMillis()
                            dao.finalizeRecording(
                                id = finalizedId,
                                endedAt = now,
                                durationMillis = (now - startMs).coerceAtLeast(1000L),
                                fileSizeBytes = finalizedFile.length(),
                                status = SegmentStatus.PENDING_UPLOAD
                            )
                            completedCycles++
                            DriveUploadWorker.enqueueAllPending(applicationContext, finalizedId)
                        } else if (finalizedId > 0L) {
                            runCatching { finalizedFile?.delete() }
                            dao.deleteEntry(finalizedId)
                        }

                        if (shouldRotateNext) {
                            launch(Dispatchers.Main) {
                                startNewCameraSegment()
                            }
                        } else if (isStopping || err != null) {
                            launch(Dispatchers.Main) {
                                finishStopCleanup(err)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startCycleWatcher() {
        watcherJob?.cancel()
        watcherJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && !isStopping) {
                delay(2000L)
                val state = _runtimeState.value
                if (state.phase != CameraCapturePhase.RECORDING) continue

                val cycleSec = currentSettings.cycleMinutes * 60L
                val stats = StorageStatsProvider.readStats(applicationContext)
                val lowStorage = stats.freeMb <= currentSettings.minFreeStorageReserveMb

                if (!rotateOnFinalize &&
                    (state.segmentElapsedSeconds >= cycleSec || (lowStorage && state.segmentElapsedSeconds >= 15L))
                ) {
                    rotateOnFinalize = true
                    launch(Dispatchers.Main) {
                        activeRecording?.stop()
                    }
                }
            }
        }
    }

    private fun stopCameraVaultGracefully(errorMessage: String?) {
        if (isStopping) return
        isStopping = true
        rotateOnFinalize = false
        watcherJob?.cancel()

        val rec = activeRecording
        activeRecording = null
        if (rec != null) {
            runCatching { rec.stop() }
        } else {
            finishStopCleanup(errorMessage)
        }
    }

    private fun finishStopCleanup(errorMessage: String?) {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        videoCapture = null
        releaseWakeLock()

        _runtimeState.value = CameraRuntimeState(
            phase = if (errorMessage != null) CameraCapturePhase.ERROR else CameraCapturePhase.IDLE,
            useFrontCamera = useFrontCamera,
            lastError = errorMessage
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VaultRec::CameraWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        watcherJob?.cancel()
        runCatching { activeRecording?.stop() }
        runCatching { cameraProvider?.unbindAll() }
        releaseWakeLock()
        super.onDestroy()
    }
}
