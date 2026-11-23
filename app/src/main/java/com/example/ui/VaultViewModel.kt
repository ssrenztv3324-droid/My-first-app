package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.drive.DriveAuthOutcome
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.local.PinSecurity
import com.example.data.local.RecordingMode
import com.example.data.local.RecordingSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.StorageStats
import com.example.data.local.StorageStatsProvider
import com.example.data.local.VaultDatabase
import com.example.data.local.VaultPreferences
import com.example.data.local.VaultSettings
import com.example.data.local.VideoQualityPreset
import com.example.service.CapturePhase
import com.example.service.ScreenVaultService
import com.example.service.VaultRuntimeState
import com.example.worker.DriveUploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class VaultUiState(
    val settings: VaultSettings = VaultSettings(),
    val runtime: VaultRuntimeState = VaultRuntimeState(),
    val storageStats: StorageStats = StorageStats(0L, 0L, 0L, 0),
    val segments: List<RecordingSegmentEntity> = emptyList(),
    val totalPurgedBytes: Long = 0L,
    val uploadedCount: Int = 0,
    val isSessionSignedIn: Boolean = false,
    val isBlackoutActive: Boolean = false,
    val blackoutCountdownSec: Int? = null,
    val authStatusMessage: String? = null,
    val isAuthorizingDrive: Boolean = false,
    val pinLockoutRemainingSec: Long = 0L,
    val pinErrorMessage: String? = null
)

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefs = VaultPreferences(appContext)
    private val dao = VaultDatabase.getInstance(appContext).segmentDao()
    private val authManager = GoogleDriveAuthManager(appContext)

    private val _storageStats = MutableStateFlow(StorageStatsProvider.readStats(appContext))
    // Require Google Drive sign-in each fresh launch to sync storage quota
    private val _isSessionSignedIn = MutableStateFlow(false)
    private val _isBlackoutActive = MutableStateFlow(false)
    private val _blackoutCountdown = MutableStateFlow<Int?>(null)
    private val _authMessage = MutableStateFlow<String?>(null)
    private val _isAuthorizing = MutableStateFlow(false)
    private val _pinLockoutRemainingSec = MutableStateFlow(0L)
    private val _pinErrorMessage = MutableStateFlow<String?>(null)

    private val _pendingConsentIntent = MutableStateFlow<PendingIntent?>(null)
    val pendingConsentIntent: StateFlow<PendingIntent?> = _pendingConsentIntent.asStateFlow()

    val uiState: StateFlow<VaultUiState> = combine(
        prefs.settingsFlow,
        ScreenVaultService.runtimeState,
        _storageStats,
        dao.observeAllSegments(),
        dao.observeTotalPurgedBytes(),
        dao.observeUploadedCount(),
        _isSessionSignedIn,
        _isBlackoutActive,
        _blackoutCountdown,
        _authMessage,
        _isAuthorizing,
        _pinLockoutRemainingSec,
        _pinErrorMessage
    ) { values ->
        val settings = values[0] as VaultSettings
        val runtime = values[1] as VaultRuntimeState
        val storage = values[2] as StorageStats
        @Suppress("UNCHECKED_CAST")
        val segments = values[3] as List<RecordingSegmentEntity>
        val purgedBytes = values[4] as Long
        val uploadedCount = values[5] as Int
        val signedIn = values[6] as Boolean
        val blackout = values[7] as Boolean
        val countdown = values[8] as Int?
        val authMsg = values[9] as String?
        val authorizing = values[10] as Boolean
        val lockoutSec = values[11] as Long
        val pinErr = values[12] as String?

        VaultUiState(
            settings = settings,
            runtime = runtime,
            storageStats = storage,
            segments = segments,
            totalPurgedBytes = purgedBytes,
            uploadedCount = uploadedCount,
            isSessionSignedIn = signedIn,
            isBlackoutActive = blackout,
            blackoutCountdownSec = countdown,
            authStatusMessage = authMsg,
            isAuthorizingDrive = authorizing,
            pinLockoutRemainingSec = lockoutSec,
            pinErrorMessage = pinErr
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = VaultUiState()
    )

    init {
        viewModelScope.launch {
            while (isActive) {
                _storageStats.value = StorageStatsProvider.readStats(appContext)
                val lockoutUntil = uiState.value.settings.pinLockoutUntilMillis
                val now = System.currentTimeMillis()
                _pinLockoutRemainingSec.value = if (lockoutUntil > now) {
                    ((lockoutUntil - now) + 999L) / 1000L
                } else {
                    0L
                }
                delay(1000L)
            }
        }
    }

    fun requestDriveAuthorization() {
        viewModelScope.launch {
            _isAuthorizing.value = true
            _authMessage.value = null
            when (val outcome = authManager.requestAuthorization()) {
                is DriveAuthOutcome.Authorized -> {
                    prefs.setConnectedAccount(
                        email = outcome.email ?: "Google Account",
                        driveUsedBytes = outcome.quota?.usedBytes,
                        driveLimitBytes = outcome.quota?.limitBytes
                    )
                    _isSessionSignedIn.value = true
                    _authMessage.value = null
                    DriveUploadWorker.enqueueAllPending(appContext)
                }
                is DriveAuthOutcome.ResolutionRequired -> {
                    _pendingConsentIntent.value = outcome.pendingIntent
                }
                is DriveAuthOutcome.Failed -> {
                    _authMessage.value = outcome.message
                }
            }
            _isAuthorizing.value = false
        }
    }

    fun consumePendingConsentIntent() {
        _pendingConsentIntent.value = null
    }

    fun handleDriveConsentResult(data: Intent?) {
        viewModelScope.launch {
            _isAuthorizing.value = true
            when (val outcome = authManager.handleConsentResult(data)) {
                is DriveAuthOutcome.Authorized -> {
                    prefs.setConnectedAccount(
                        email = outcome.email ?: "Google Account",
                        driveUsedBytes = outcome.quota?.usedBytes,
                        driveLimitBytes = outcome.quota?.limitBytes
                    )
                    _isSessionSignedIn.value = true
                    _authMessage.value = null
                    DriveUploadWorker.enqueueAllPending(appContext)
                }
                is DriveAuthOutcome.Failed -> {
                    _authMessage.value = outcome.message
                }
                is DriveAuthOutcome.ResolutionRequired -> Unit
            }
            _isAuthorizing.value = false
        }
    }

    fun signOutSession() {
        viewModelScope.launch {
            if (ScreenVaultService.runtimeState.value.phase != CapturePhase.IDLE) {
                appContext.startService(ScreenVaultService.stopIntent(appContext))
            }
            if (com.example.service.CameraVaultService.runtimeState.value.phase !=
                com.example.service.CameraCapturePhase.IDLE
            ) {
                appContext.startService(com.example.service.CameraVaultService.stopIntent(appContext))
            }
            _isBlackoutActive.value = false
            _blackoutCountdown.value = null
            _isSessionSignedIn.value = false
            _authMessage.value = null
            prefs.clearSessionAccount()
        }
    }

    fun savePin(pin: String) {
        viewModelScope.launch {
            prefs.savePin(pin)
            _pinErrorMessage.value = null
            _pinLockoutRemainingSec.value = 0L
        }
    }

    fun attemptPinUnlock(pin: String): Boolean {
        val current = uiState.value.settings
        val now = System.currentTimeMillis()
        if (current.pinLockoutUntilMillis > now) {
            _pinLockoutRemainingSec.value = ((current.pinLockoutUntilMillis - now) + 999L) / 1000L
            return false
        }

        val isValid = if (!current.hasPinConfigured) {
            true
        } else {
            PinSecurity.verifyPin(pin, current.pinSalt.orEmpty(), current.pinHash.orEmpty())
        }

        if (isValid) {
            viewModelScope.launch {
                prefs.resetPinFailures()
            }
            _pinErrorMessage.value = null
            _pinLockoutRemainingSec.value = 0L
            _isBlackoutActive.value = false
            return true
        } else {
            val newFailures = current.failedPinAttempts + 1
            val cooldownSec = PinSecurity.cooldownSecondsForFailures(newFailures)
            val lockoutUntil = if (cooldownSec > 0L) now + (cooldownSec * 1000L) else 0L
            viewModelScope.launch {
                prefs.recordPinFailure(newFailures, lockoutUntil)
            }
            if (cooldownSec > 0L) {
                _pinLockoutRemainingSec.value = cooldownSec
                _pinErrorMessage.value = "ใส่รหัสผิดครบ $newFailures ครั้ง กรุณารอ ${cooldownSec} วินาที"
            } else {
                val remainingTries = (PinSecurity.MAX_ATTEMPTS_BEFORE_COOLDOWN - newFailures).coerceAtLeast(1)
                _pinErrorMessage.value = "รหัส PIN ไม่ถูกต้อง (เหลืออีก $remainingTries ครั้งก่อนล็อก)"
            }
            return false
        }
    }

    fun clearPinError() {
        _pinErrorMessage.value = null
    }

    fun onRecordingStarted() {
        val delaySec = uiState.value.settings.autoBlackoutDelaySec
        if (delaySec <= 0) {
            _blackoutCountdown.value = null
            _isBlackoutActive.value = true
        } else {
            viewModelScope.launch {
                for (rem in delaySec downTo 1) {
                    if (ScreenVaultService.runtimeState.value.phase == CapturePhase.IDLE && rem < delaySec) {
                        _blackoutCountdown.value = null
                        return@launch
                    }
                    _blackoutCountdown.value = rem
                    delay(1000L)
                }
                _blackoutCountdown.value = null
                _isBlackoutActive.value = true
            }
        }
    }

    fun enterBlackoutMode() {
        _blackoutCountdown.value = null
        _isBlackoutActive.value = true
    }

    fun cancelAutoBlackout() {
        _blackoutCountdown.value = null
    }

    fun setRecordingMode(mode: RecordingMode) {
        viewModelScope.launch { prefs.setRecordingMode(mode) }
    }

    fun setCycleMinutes(minutes: Int) {
        viewModelScope.launch { prefs.setCycleMinutes(minutes) }
    }

    fun setVideoQuality(preset: VideoQualityPreset) {
        viewModelScope.launch { prefs.setVideoQuality(preset) }
    }

    fun setAutoDeleteAfterUpload(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoDeleteAfterUpload(enabled) }
    }

    fun setAutoBlackoutDelaySec(seconds: Int) {
        viewModelScope.launch { prefs.setAutoBlackoutDelaySec(seconds) }
    }

    fun setMotionSensitivity(level: Int) {
        viewModelScope.launch { prefs.setMotionSensitivity(level) }
    }

    fun setMotionClipDurationSec(seconds: Int) {
        viewModelScope.launch { prefs.setMotionClipDurationSec(seconds) }
    }

    fun setMinFreeStorageReserveMb(mb: Int) {
        viewModelScope.launch { prefs.setMinFreeStorageReserveMb(mb) }
    }

    fun setStealthNotificationTitle(title: String) {
        viewModelScope.launch { prefs.setStealthNotificationTitle(title) }
    }

    fun retryUpload(segment: RecordingSegmentEntity) {
        viewModelScope.launch {
            dao.updateUploadState(segment.id, SegmentStatus.PENDING_UPLOAD, 0, null)
            DriveUploadWorker.enqueueAllPending(appContext, segment.id)
        }
    }

    fun retryAllPending() {
        DriveUploadWorker.enqueueAllPending(appContext)
    }

    fun purgeLocalFileNow(segment: RecordingSegmentEntity) {
        viewModelScope.launch {
            runCatching { File(segment.localFilePath).delete() }
            if (segment.driveFileId != null) {
                dao.markLocalPurged(segment.id)
            } else {
                dao.deleteEntry(segment.id)
            }
            _storageStats.value = StorageStatsProvider.readStats(appContext)
        }
    }

    fun clearCompletedHistory() {
        viewModelScope.launch {
            dao.clearCompletedLogs()
        }
    }
}
