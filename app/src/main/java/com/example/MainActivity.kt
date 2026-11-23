package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.RecordingMode
import com.example.service.ScreenVaultService
import com.example.ui.BlackoutOverlay
import com.example.ui.DriveSignInGateScreen
import com.example.ui.QueueScreen
import com.example.ui.SettingsScreen
import com.example.ui.SimpleVaultHomeScreen
import com.example.ui.VaultViewModel
import com.example.ui.VideoCaptureViewModel
import com.example.ui.theme.VaultBg
import com.example.ui.theme.VaultRecTheme
import com.example.ui.theme.VaultSurface
import kotlinx.serialization.Serializable

@Serializable
object ControlRoute

@Serializable
object QueueRoute

@Serializable
object SettingsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VaultRecTheme {
                VaultRecApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultRecApp(
    viewModel: VaultViewModel = viewModel(),
    videoCaptureViewModel: VideoCaptureViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val videoCaptureState by videoCaptureViewModel.uiState.collectAsStateWithLifecycle()
    val pendingConsent by viewModel.pendingConsentIntent.collectAsStateWithLifecycle()

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = ScreenVaultService.startIntent(context, result.resultCode, data)
            ContextCompat.startForegroundService(context, serviceIntent)
            viewModel.onRecordingStarted()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        videoCaptureViewModel.setCameraPermissionGranted(granted)
        if (granted) {
            videoCaptureViewModel.startSilentBackgroundRecording()
            viewModel.onRecordingStarted()
        }
    }

    val launchCaptureByMode: () -> Unit = {
        if (state.settings.recordingMode == RecordingMode.CAMERA_BACKGROUND) {
            if (videoCaptureViewModel.refreshCameraPermission()) {
                videoCaptureViewModel.startSilentBackgroundRecording()
                viewModel.onRecordingStarted()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(captureIntent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        launchCaptureByMode()
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleDriveConsentResult(result.data)
    }

    LaunchedEffect(pendingConsent) {
        val intent = pendingConsent
        if (intent != null) {
            viewModel.consumePendingConsentIntent()
            driveConsentLauncher.launch(
                IntentSenderRequest.Builder(intent.intentSender).build()
            )
        }
    }

    val startSelectedRecording: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchCaptureByMode()
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Box(modifier = Modifier.fillMaxSize()) {
        if (!state.isSessionSignedIn) {
            Scaffold(
                containerColor = VaultBg,
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                DriveSignInGateScreen(
                    state = state,
                    onSignInClick = viewModel::requestDriveAuthorization,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        } else {
            val showSubBar = currentRoute?.contains("QueueRoute") == true ||
                currentRoute?.contains("SettingsRoute") == true

            Scaffold(
                containerColor = VaultBg,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    if (showSubBar) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (currentRoute?.contains("QueueRoute") == true) {
                                        stringResource(R.string.nav_queue)
                                    } else {
                                        stringResource(R.string.nav_settings)
                                    }
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "ย้อนกลับ"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = VaultSurface
                            )
                        )
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ControlRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable<ControlRoute> {
                        SimpleVaultHomeScreen(
                            state = state,
                            videoCaptureState = videoCaptureState,
                            onSelectMode = viewModel::setRecordingMode,
                            onToggleCameraLens = videoCaptureViewModel::toggleCameraLens,
                            onSavePin = viewModel::savePin,
                            onStartRecording = startSelectedRecording,
                            onStopRecording = {
                                context.startService(ScreenVaultService.stopIntent(context))
                                videoCaptureViewModel.stopSilentBackgroundRecording()
                                viewModel.cancelAutoBlackout()
                            },
                            onSignOut = viewModel::signOutSession,
                            onOpenQueue = { navController.navigate(QueueRoute) },
                            onOpenSettings = { navController.navigate(SettingsRoute) }
                        )
                    }
                    composable<QueueRoute> {
                        QueueScreen(
                            state = state,
                            onRetrySegment = viewModel::retryUpload,
                            onRetryAll = viewModel::retryAllPending,
                            onPurgeLocal = viewModel::purgeLocalFileNow,
                            onClearCompleted = viewModel::clearCompletedHistory
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            state = state,
                            onCycleMinutesChange = viewModel::setCycleMinutes,
                            onQualityChange = viewModel::setVideoQuality,
                            onAutoDeleteChange = viewModel::setAutoDeleteAfterUpload,
                            onAutoBlackoutDelayChange = viewModel::setAutoBlackoutDelaySec,
                            onMotionSensitivityChange = viewModel::setMotionSensitivity,
                            onMotionClipDurationChange = viewModel::setMotionClipDurationSec,
                            onMinReserveMbChange = viewModel::setMinFreeStorageReserveMb,
                            onStealthTitleChange = viewModel::setStealthNotificationTitle,
                            onSavePin = viewModel::savePin
                        )
                    }
                }
            }
        }

        if (state.isBlackoutActive) {
            BlackoutOverlay(
                hasPinConfigured = state.settings.hasPinConfigured,
                lockoutRemainingSec = state.pinLockoutRemainingSec,
                pinErrorMessage = state.pinErrorMessage,
                onAttemptPinUnlock = viewModel::attemptPinUnlock,
                onClearPinError = viewModel::clearPinError
            )
        }
    }
}
