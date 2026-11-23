package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.CameraCapturePhase
import com.example.service.CameraRuntimeState
import com.example.service.CameraVaultService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class VideoCaptureUiState(
    val hasCameraPermission: Boolean = false,
    val useFrontCamera: Boolean = false,
    val runtime: CameraRuntimeState = CameraRuntimeState()
) {
    val isRecording: Boolean
        get() = runtime.phase == CameraCapturePhase.RECORDING ||
            runtime.phase == CameraCapturePhase.PREPARING
}

class VideoCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val _hasCameraPermission = MutableStateFlow(checkCameraPermission())
    private val _useFrontCamera = MutableStateFlow(false)

    val uiState: StateFlow<VideoCaptureUiState> = combine(
        _hasCameraPermission,
        _useFrontCamera,
        CameraVaultService.runtimeState
    ) { hasPermission, useFront, runtime ->
        VideoCaptureUiState(
            hasCameraPermission = hasPermission,
            useFrontCamera = useFront,
            runtime = runtime
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = VideoCaptureUiState(
            hasCameraPermission = _hasCameraPermission.value,
            useFrontCamera = _useFrontCamera.value
        )
    )

    fun refreshCameraPermission(): Boolean {
        val granted = checkCameraPermission()
        _hasCameraPermission.value = granted
        return granted
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        _hasCameraPermission.value = granted
    }

    fun toggleCameraLens() {
        if (!uiState.value.isRecording) {
            _useFrontCamera.value = !_useFrontCamera.value
        }
    }

    fun startSilentBackgroundRecording() {
        val granted = refreshCameraPermission()
        if (!granted) return
        val intent = CameraVaultService.startIntent(
            context = appContext,
            useFrontCamera = _useFrontCamera.value
        )
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopSilentBackgroundRecording() {
        appContext.startService(CameraVaultService.stopIntent(appContext))
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
