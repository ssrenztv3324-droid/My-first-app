package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_prefs")

enum class RecordingMode(val labelTh: String) {
    CONTINUOUS("อัดต่อเนื่อง"),
    MOTION_DETECT("จับความเคลื่อนไหว"),
    CAMERA_BACKGROUND("กล้องเงียบ (CameraX)")
}

enum class VideoQualityPreset(
    val labelTh: String,
    val shortSpec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val approxMbPer2Hours: Int
) {
    COMPACT_360P(
        labelTh = "ประหยัดพื้นที่สูงสุด (360p)",
        shortSpec = "360p • 15fps • 500kbps",
        width = 360,
        height = 640,
        fps = 15,
        bitrateBps = 500_000,
        approxMbPer2Hours = 450
    ),
    BALANCED_480P(
        labelTh = "แนะนำสำหรับเครื่อง 32GB (480p)",
        shortSpec = "480p • 20fps • 800kbps",
        width = 480,
        height = 854,
        fps = 20,
        bitrateBps = 800_000,
        approxMbPer2Hours = 720
    ),
    SHARP_720P(
        labelTh = "คมชัดสูง (720p)",
        shortSpec = "720p • 24fps • 1.5Mbps",
        width = 720,
        height = 1280,
        fps = 24,
        bitrateBps = 1_500_000,
        approxMbPer2Hours = 1350
    )
}

data class VaultSettings(
    val recordingMode: RecordingMode = RecordingMode.CONTINUOUS,
    val cycleMinutes: Int = 120,
    val videoQuality: VideoQualityPreset = VideoQualityPreset.BALANCED_480P,
    val autoDeleteAfterUpload: Boolean = true,
    val autoBlackoutDelaySec: Int = 0, // 0 = immediate pitch black when recording starts
    val motionSensitivity: Int = 2,
    val motionClipDurationSec: Int = 60,
    val minFreeStorageReserveMb: Int = 1024,
    val recordAudio: Boolean = false,
    val connectedEmail: String? = null,
    val driveFolderId: String? = null,
    val driveUsedBytes: Long = 0L,
    val driveLimitBytes: Long = 0L,
    val stealthNotificationTitle: String = "Sync Active",
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val failedPinAttempts: Int = 0,
    val pinLockoutUntilMillis: Long = 0L
) {
    val hasPinConfigured: Boolean get() = !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()
}

class VaultPreferences(private val context: Context) {

    private object Keys {
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val CYCLE_MINUTES = intPreferencesKey("cycle_minutes")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val AUTO_DELETE = booleanPreferencesKey("auto_delete_after_upload")
        val AUTO_BLACKOUT_DELAY = intPreferencesKey("auto_blackout_delay_sec")
        val MOTION_SENSITIVITY = intPreferencesKey("motion_sensitivity")
        val MOTION_CLIP_DURATION = intPreferencesKey("motion_clip_duration_sec")
        val MIN_FREE_RESERVE_MB = intPreferencesKey("min_free_reserve_mb")
        val RECORD_AUDIO = booleanPreferencesKey("record_audio")
        val CONNECTED_EMAIL = stringPreferencesKey("connected_email")
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val DRIVE_USED_BYTES = longPreferencesKey("drive_used_bytes")
        val DRIVE_LIMIT_BYTES = longPreferencesKey("drive_limit_bytes")
        val STEALTH_NOTIF_TITLE = stringPreferencesKey("stealth_notif_title")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val FAILED_PIN_ATTEMPTS = intPreferencesKey("failed_pin_attempts")
        val PIN_LOCKOUT_UNTIL = longPreferencesKey("pin_lockout_until")
    }

    val settingsFlow: Flow<VaultSettings> = context.vaultDataStore.data.map { prefs ->
        VaultSettings(
            recordingMode = prefs[Keys.RECORDING_MODE]?.let {
                runCatching { RecordingMode.valueOf(it) }.getOrNull()
            } ?: RecordingMode.CONTINUOUS,
            cycleMinutes = prefs[Keys.CYCLE_MINUTES] ?: 120,
            videoQuality = prefs[Keys.VIDEO_QUALITY]?.let {
                runCatching { VideoQualityPreset.valueOf(it) }.getOrNull()
            } ?: VideoQualityPreset.BALANCED_480P,
            autoDeleteAfterUpload = prefs[Keys.AUTO_DELETE] ?: true,
            autoBlackoutDelaySec = prefs[Keys.AUTO_BLACKOUT_DELAY] ?: 0,
            motionSensitivity = prefs[Keys.MOTION_SENSITIVITY] ?: 2,
            motionClipDurationSec = prefs[Keys.MOTION_CLIP_DURATION] ?: 60,
            minFreeStorageReserveMb = prefs[Keys.MIN_FREE_RESERVE_MB] ?: 1024,
            recordAudio = prefs[Keys.RECORD_AUDIO] ?: false,
            connectedEmail = prefs[Keys.CONNECTED_EMAIL]?.takeIf { it.isNotBlank() },
            driveFolderId = prefs[Keys.DRIVE_FOLDER_ID]?.takeIf { it.isNotBlank() },
            driveUsedBytes = prefs[Keys.DRIVE_USED_BYTES] ?: 0L,
            driveLimitBytes = prefs[Keys.DRIVE_LIMIT_BYTES] ?: 0L,
            stealthNotificationTitle = prefs[Keys.STEALTH_NOTIF_TITLE]?.takeIf { it.isNotBlank() }
                ?: "Sync Active",
            pinHash = prefs[Keys.PIN_HASH],
            pinSalt = prefs[Keys.PIN_SALT],
            failedPinAttempts = prefs[Keys.FAILED_PIN_ATTEMPTS] ?: 0,
            pinLockoutUntilMillis = prefs[Keys.PIN_LOCKOUT_UNTIL] ?: 0L
        )
    }

    suspend fun setRecordingMode(mode: RecordingMode) {
        context.vaultDataStore.edit { it[Keys.RECORDING_MODE] = mode.name }
    }

    suspend fun setCycleMinutes(minutes: Int) {
        context.vaultDataStore.edit { it[Keys.CYCLE_MINUTES] = minutes }
    }

    suspend fun setVideoQuality(preset: VideoQualityPreset) {
        context.vaultDataStore.edit { it[Keys.VIDEO_QUALITY] = preset.name }
    }

    suspend fun setAutoDeleteAfterUpload(enabled: Boolean) {
        context.vaultDataStore.edit { it[Keys.AUTO_DELETE] = enabled }
    }

    suspend fun setAutoBlackoutDelaySec(seconds: Int) {
        context.vaultDataStore.edit { it[Keys.AUTO_BLACKOUT_DELAY] = seconds }
    }

    suspend fun setMotionSensitivity(level: Int) {
        context.vaultDataStore.edit { it[Keys.MOTION_SENSITIVITY] = level.coerceIn(1, 3) }
    }

    suspend fun setMotionClipDurationSec(seconds: Int) {
        context.vaultDataStore.edit { it[Keys.MOTION_CLIP_DURATION] = seconds }
    }

    suspend fun setMinFreeStorageReserveMb(mb: Int) {
        context.vaultDataStore.edit { it[Keys.MIN_FREE_RESERVE_MB] = mb }
    }

    suspend fun setConnectedAccount(
        email: String?,
        folderId: String? = null,
        driveUsedBytes: Long? = null,
        driveLimitBytes: Long? = null
    ) {
        context.vaultDataStore.edit {
            if (email != null) it[Keys.CONNECTED_EMAIL] = email else it.remove(Keys.CONNECTED_EMAIL)
            if (folderId != null) it[Keys.DRIVE_FOLDER_ID] = folderId
            if (driveUsedBytes != null) it[Keys.DRIVE_USED_BYTES] = driveUsedBytes
            if (driveLimitBytes != null) it[Keys.DRIVE_LIMIT_BYTES] = driveLimitBytes
        }
    }

    suspend fun clearSessionAccount() {
        context.vaultDataStore.edit {
            it.remove(Keys.CONNECTED_EMAIL)
            it.remove(Keys.DRIVE_FOLDER_ID)
            it.remove(Keys.DRIVE_USED_BYTES)
            it.remove(Keys.DRIVE_LIMIT_BYTES)
        }
    }

    suspend fun setDriveFolderId(folderId: String) {
        context.vaultDataStore.edit { it[Keys.DRIVE_FOLDER_ID] = folderId }
    }

    suspend fun setStealthNotificationTitle(title: String) {
        context.vaultDataStore.edit {
            it[Keys.STEALTH_NOTIF_TITLE] = title.trim().ifEmpty { "Sync Active" }
        }
    }

    suspend fun savePin(pin: String) {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin(pin, salt)
        context.vaultDataStore.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash
            it[Keys.FAILED_PIN_ATTEMPTS] = 0
            it[Keys.PIN_LOCKOUT_UNTIL] = 0L
        }
    }

    suspend fun recordPinFailure(attempts: Int, lockoutUntilMillis: Long) {
        context.vaultDataStore.edit {
            it[Keys.FAILED_PIN_ATTEMPTS] = attempts
            it[Keys.PIN_LOCKOUT_UNTIL] = lockoutUntilMillis
        }
    }

    suspend fun resetPinFailures() {
        context.vaultDataStore.edit {
            it[Keys.FAILED_PIN_ATTEMPTS] = 0
            it[Keys.PIN_LOCKOUT_UNTIL] = 0L
        }
    }
}
