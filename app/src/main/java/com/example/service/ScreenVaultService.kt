package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.RecordingMode
import com.example.data.local.RecordingSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.StorageStatsProvider
import com.example.data.local.VaultDatabase
import com.example.data.local.VaultPreferences
import com.example.data.local.VaultSettings
import com.example.worker.DriveUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

enum class CapturePhase {
    IDLE,
    RECORDING_CONTINUOUS,
    MOTION_ARMED,
    RECORDING_MOTION
}

data class VaultRuntimeState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val activeSegmentFileName: String? = null,
    val activeSegmentBytes: Long = 0L,
    val segmentElapsedSeconds: Long = 0L,
    val cycleTargetSeconds: Long = 7200L,
    val totalSessionSeconds: Long = 0L,
    val completedCyclesCount: Int = 0,
    val lastMotionPercent: Float = 0f,
    val lastError: String? = null
)

class ScreenVaultService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_VAULT"
        const val ACTION_STOP = "com.example.action.STOP_VAULT"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        private const val CHANNEL_ID = "vaultrec_silent_sync_v1"
        private const val NOTIFICATION_ID = 4091
        private const val MONITOR_WIDTH = 360
        private const val MONITOR_HEIGHT = 640

        private val _runtimeState = MutableStateFlow(VaultRuntimeState())
        val runtimeState: StateFlow<VaultRuntimeState> = _runtimeState.asStateFlow()

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenVaultService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenVaultService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val motionDetector = MotionDetector()

    private var currentSettings = VaultSettings()
    private var activeSegmentId: Long = -1L
    private var activeFile: File? = null
    private var segmentStartMillis: Long = 0L
    private var sessionStartMillis: Long = 0L
    private var completedCycles: Int = 0
    private var lastFrameAnalyzedMillis: Long = 0L
    private var motionHoldUntilMillis: Long = 0L
    private var displayDensityDpi: Int = DisplayMetrics.DENSITY_DEFAULT
    @Volatile
    private var isStopping = false
    @Volatile
    private var motionTriggerInProgress = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            serviceScope.launch {
                stopCaptureGracefully(errorMessage = null)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createSilentNotificationChannel()
        handlerThread = HandlerThread("VaultCaptureThread").apply { start() }
        captureHandler = Handler(handlerThread!!.looper)

        val windowManager = getSystemService(WindowManager::class.java)
        displayDensityDpi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            dm.densityDpi
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    stopCaptureGracefully(errorMessage = null)
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundSilent("Sync Active")

                serviceScope.launch {
                    val prefs = VaultPreferences(applicationContext)
                    currentSettings = prefs.settingsFlow.first()
                    startForegroundSilent(currentSettings.stealthNotificationTitle)
                    beginProjection(resultCode, dataIntent)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSilent(title: String) {
        val notification = buildSilentNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
            100,
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

    private suspend fun beginProjection(resultCode: Int, dataIntent: Intent) {
        try {
            isStopping = false
            completedCycles = 0
            sessionStartMillis = System.currentTimeMillis()
            acquireWakeLock()

            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, dataIntent)
                ?: error("ไม่สามารถเริ่มต้น MediaProjection ได้")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, captureHandler)

            if (currentSettings.recordingMode == RecordingMode.CONTINUOUS) {
                startContinuousCapture(projection)
            } else {
                startMotionMonitoring(projection)
            }

            startTickerLoop()
        } catch (e: Exception) {
            stopCaptureGracefully(errorMessage = e.localizedMessage ?: "เริ่มบันทึกหน้าจอไม่สำเร็จ")
        }
    }

    private suspend fun startContinuousCapture(projection: MediaProjection) {
        val preset = currentSettings.videoQuality
        val (recorder, file, segId) = prepareAndStartRecorder(
            width = preset.width,
            height = preset.height,
            fps = preset.fps,
            bitrate = preset.bitrateBps,
            mode = RecordingMode.CONTINUOUS
        )
        mediaRecorder = recorder
        activeFile = file
        activeSegmentId = segId
        segmentStartMillis = System.currentTimeMillis()

        virtualDisplay = projection.createVirtualDisplay(
            "VaultRecDisplay",
            preset.width,
            preset.height,
            displayDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            captureHandler
        )

        _runtimeState.value = VaultRuntimeState(
            phase = CapturePhase.RECORDING_CONTINUOUS,
            activeSegmentFileName = file.name,
            activeSegmentBytes = 0L,
            segmentElapsedSeconds = 0L,
            cycleTargetSeconds = currentSettings.cycleMinutes * 60L,
            totalSessionSeconds = 0L,
            completedCyclesCount = completedCycles
        )
    }

    private fun startMotionMonitoring(projection: MediaProjection) {
        motionDetector.reset()
        val reader = ImageReader.newInstance(
            MONITOR_WIDTH,
            MONITOR_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "VaultRecMotionDisplay",
            MONITOR_WIDTH,
            MONITOR_HEIGHT,
            displayDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (_runtimeState.value.phase != CapturePhase.MOTION_ARMED) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameAnalyzedMillis < 450L) return@setOnImageAvailableListener
                lastFrameAnalyzedMillis = now

                val deltaPct = motionDetector.computeChangePercent(img)
                val threshold = MotionDetector.thresholdForSensitivity(currentSettings.motionSensitivity)
                _runtimeState.value = _runtimeState.value.copy(lastMotionPercent = deltaPct)

                if (deltaPct >= threshold && !motionTriggerInProgress) {
                    motionTriggerInProgress = true
                    serviceScope.launch {
                        triggerMotionRecording()
                    }
                }
            } finally {
                img.close()
            }
        }, captureHandler)

        _runtimeState.value = VaultRuntimeState(
            phase = CapturePhase.MOTION_ARMED,
            activeSegmentFileName = null,
            activeSegmentBytes = 0L,
            segmentElapsedSeconds = 0L,
            cycleTargetSeconds = currentSettings.motionClipDurationSec.toLong(),
            totalSessionSeconds = 0L,
            completedCyclesCount = completedCycles
        )
    }

    @Synchronized
    private fun createNewSegmentFile(prefix: String): File {
        val dir = StorageStatsProvider.getRecordingsDir(applicationContext)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${prefix}_$stamp.mp4")
    }

    private suspend fun prepareAndStartRecorder(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        mode: RecordingMode
    ): Triple<MediaRecorder, File, Long> {
        val prefix = if (mode == RecordingMode.CONTINUOUS) "VAULT_2H" else "VAULT_MOTION"
        val outFile = createNewSegmentFile(prefix)
        val now = System.currentTimeMillis()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outFile.absolutePath)
            setVideoSize(width, height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            prepare()
            start()
        }

        val dao = VaultDatabase.getInstance(applicationContext).segmentDao()
        val segId = dao.insert(
            RecordingSegmentEntity(
                fileName = outFile.name,
                localFilePath = outFile.absolutePath,
                triggerMode = mode.name,
                startedAtMillis = now,
                status = SegmentStatus.RECORDING
            )
        )

        return Triple(recorder, outFile, segId)
    }

    private suspend fun finalizeCurrentRecorderAndEnqueue() {
        val recorder = mediaRecorder
        mediaRecorder = null
        val file = activeFile
        activeFile = null
        val segId = activeSegmentId
        activeSegmentId = -1L

        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }

        if (segId > 0L && file != null) {
            val now = System.currentTimeMillis()
            val duration = (now - segmentStartMillis).coerceAtLeast(1000L)
            val size = if (file.exists()) file.length() else 0L
            val dao = VaultDatabase.getInstance(applicationContext).segmentDao()

            if (size > 0L) {
                dao.finalizeRecording(
                    id = segId,
                    endedAt = now,
                    durationMillis = duration,
                    fileSizeBytes = size,
                    status = SegmentStatus.PENDING_UPLOAD
                )
                completedCycles++
                DriveUploadWorker.enqueueAllPending(applicationContext, segId)
            } else {
                runCatching { file.delete() }
                dao.deleteEntry(segId)
            }
        }
    }

    private suspend fun rotateContinuousSegment() {
        if (isStopping || mediaProjection == null || virtualDisplay == null) return
        val preset = currentSettings.videoQuality

        // Temporarily detach surface before releasing old recorder
        virtualDisplay?.surface = null
        finalizeCurrentRecorderAndEnqueue()

        val (nextRecorder, nextFile, nextSegId) = prepareAndStartRecorder(
            width = preset.width,
            height = preset.height,
            fps = preset.fps,
            bitrate = preset.bitrateBps,
            mode = RecordingMode.CONTINUOUS
        )
        mediaRecorder = nextRecorder
        activeFile = nextFile
        activeSegmentId = nextSegId
        segmentStartMillis = System.currentTimeMillis()

        virtualDisplay?.surface = nextRecorder.surface
    }

    private suspend fun triggerMotionRecording() {
        if (isStopping || _runtimeState.value.phase != CapturePhase.MOTION_ARMED) {
            motionTriggerInProgress = false
            return
        }
        val vd = virtualDisplay ?: run {
            motionTriggerInProgress = false
            return
        }
        val preset = currentSettings.videoQuality

        vd.surface = null
        vd.resize(preset.width, preset.height, displayDensityDpi)

        val (recorder, file, segId) = prepareAndStartRecorder(
            width = preset.width,
            height = preset.height,
            fps = preset.fps,
            bitrate = preset.bitrateBps,
            mode = RecordingMode.MOTION_DETECT
        )
        mediaRecorder = recorder
        activeFile = file
        activeSegmentId = segId
        segmentStartMillis = System.currentTimeMillis()
        motionHoldUntilMillis = segmentStartMillis + (currentSettings.motionClipDurationSec * 1000L)

        vd.surface = recorder.surface

        _runtimeState.value = _runtimeState.value.copy(
            phase = CapturePhase.RECORDING_MOTION,
            activeSegmentFileName = file.name,
            activeSegmentBytes = 0L,
            segmentElapsedSeconds = 0L,
            cycleTargetSeconds = currentSettings.motionClipDurationSec.toLong()
        )
    }

    private suspend fun finishMotionClipAndRearm() {
        if (isStopping) return
        val vd = virtualDisplay ?: return
        val reader = imageReader ?: return

        vd.surface = null
        finalizeCurrentRecorderAndEnqueue()
        motionDetector.reset()

        vd.resize(MONITOR_WIDTH, MONITOR_HEIGHT, displayDensityDpi)
        vd.surface = reader.surface
        motionTriggerInProgress = false

        _runtimeState.value = _runtimeState.value.copy(
            phase = CapturePhase.MOTION_ARMED,
            activeSegmentFileName = null,
            activeSegmentBytes = 0L,
            segmentElapsedSeconds = 0L,
            completedCyclesCount = completedCycles
        )
    }

    private fun startTickerLoop() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive && !isStopping) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val totalSec = (now - sessionStartMillis) / 1000L
                val segSec = if (segmentStartMillis > 0L) (now - segmentStartMillis) / 1000L else 0L
                val fileBytes = activeFile?.takeIf { it.exists() }?.length() ?: 0L
                val currentPhase = _runtimeState.value.phase

                when (currentPhase) {
                    CapturePhase.RECORDING_CONTINUOUS -> {
                        val cycleTargetSec = currentSettings.cycleMinutes * 60L
                        val stats = StorageStatsProvider.readStats(applicationContext)
                        val lowStorage = stats.freeMb <= currentSettings.minFreeStorageReserveMb

                        if (segSec >= cycleTargetSec || (lowStorage && segSec >= 15L)) {
                            rotateContinuousSegment()
                        } else {
                            _runtimeState.value = _runtimeState.value.copy(
                                activeSegmentBytes = fileBytes,
                                segmentElapsedSeconds = segSec,
                                cycleTargetSeconds = cycleTargetSec,
                                totalSessionSeconds = totalSec,
                                completedCyclesCount = completedCycles
                            )
                        }
                    }
                    CapturePhase.RECORDING_MOTION -> {
                        val stats = StorageStatsProvider.readStats(applicationContext)
                        val lowStorage = stats.freeMb <= currentSettings.minFreeStorageReserveMb
                        if (now >= motionHoldUntilMillis || (lowStorage && segSec >= 10L)) {
                            finishMotionClipAndRearm()
                        } else {
                            _runtimeState.value = _runtimeState.value.copy(
                                activeSegmentBytes = fileBytes,
                                segmentElapsedSeconds = segSec,
                                totalSessionSeconds = totalSec,
                                completedCyclesCount = completedCycles
                            )
                        }
                    }
                    CapturePhase.MOTION_ARMED -> {
                        _runtimeState.value = _runtimeState.value.copy(
                            totalSessionSeconds = totalSec,
                            completedCyclesCount = completedCycles
                        )
                    }
                    CapturePhase.IDLE -> Unit
                }
            }
        }
    }

    private suspend fun stopCaptureGracefully(errorMessage: String?) {
        if (isStopping) return
        isStopping = true
        tickerJob?.cancel()

        runCatching { virtualDisplay?.surface = null }
        finalizeCurrentRecorderAndEnqueue()

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        releaseWakeLock()
        _runtimeState.value = VaultRuntimeState(
            phase = CapturePhase.IDLE,
            lastError = errorMessage
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VaultRec::CaptureWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // Max 12h safety timeout per session
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
        tickerJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        handlerThread?.quitSafely()
        super.onDestroy()
    }
}
