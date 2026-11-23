package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.local.RecordingMode
import com.example.data.local.StorageStatsProvider
import com.example.service.CapturePhase
import com.example.ui.theme.AmberMotion
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanDrive
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.VaultBg
import com.example.ui.theme.VaultOutline
import com.example.ui.theme.VaultSurface
import com.example.ui.theme.VaultSurfaceElevated

@Composable
fun DriveSignInGateScreen(
    state: VaultUiState,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = VaultSurface),
            border = BorderStroke(1.dp, VaultOutline),
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_vaultrec_icon),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                )

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "กรุณาลงชื่อเข้าใช้ Google Drive ทุกครั้งก่อนใช้งาน เพื่อซิงก์พื้นที่จัดเก็บข้อมูลและรับประกันการอัปโหลดไฟล์อัตโนมัติ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                AnimatedVisibility(visible = !state.authStatusMessage.isNullOrBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = CrimsonLive.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.authStatusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrimsonLive,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Button(
                    onClick = onSignInClick,
                    enabled = !state.isAuthorizingDrive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("gate_sign_in_button"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPulse,
                        contentColor = ObsidianBlack
                    )
                ) {
                    if (state.isAuthorizingDrive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = ObsidianBlack
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(R.string.drive_connect),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SimpleVaultHomeScreen(
    state: VaultUiState,
    videoCaptureState: VideoCaptureUiState,
    onSelectMode: (RecordingMode) -> Unit,
    onToggleCameraLens: () -> Unit,
    onSavePin: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSignOut: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state.runtime.phase != CapturePhase.IDLE || videoCaptureState.isRecording
    var showPinSetupDialog by remember { mutableStateOf(false) }

    val driveFreeText = if (state.settings.driveLimitBytes > 0L) {
        val freeBytes = (state.settings.driveLimitBytes - state.settings.driveUsedBytes).coerceAtLeast(0L)
        "Drive ว่าง ${StorageStatsProvider.formatBytes(freeBytes)}"
    } else {
        "ซิงก์ Drive แล้ว"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultBg)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar: synced Drive badge + discreet Queue & Settings icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = EmeraldContainer,
                    border = BorderStroke(1.dp, EmeraldPulse.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = EmeraldPulse,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = driveFreeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldPulse
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.testTag("open_queue_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = stringResource(R.string.nav_queue),
                            tint = CyanDrive
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("open_settings_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.nav_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Center minimal focal card: account, compact mode pill, and clean Record + Sign Out buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = state.settings.connectedEmail.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Compact mode switcher chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeMiniChip(
                        label = "จอ 2 ชม.",
                        selected = state.settings.recordingMode == RecordingMode.CONTINUOUS,
                        enabled = !isActive,
                        onClick = { onSelectMode(RecordingMode.CONTINUOUS) }
                    )
                    ModeMiniChip(
                        label = "เคลื่อนไหว",
                        selected = state.settings.recordingMode == RecordingMode.MOTION_DETECT,
                        enabled = !isActive,
                        onClick = { onSelectMode(RecordingMode.MOTION_DETECT) }
                    )
                    ModeMiniChip(
                        label = "กล้องเงียบ",
                        selected = state.settings.recordingMode == RecordingMode.CAMERA_BACKGROUND,
                        enabled = !isActive,
                        onClick = { onSelectMode(RecordingMode.CAMERA_BACKGROUND) }
                    )
                }

                if (state.settings.recordingMode == RecordingMode.CAMERA_BACKGROUND && !isActive) {
                    ModeMiniChip(
                        label = if (videoCaptureState.useFrontCamera) "สลับเลนส์: กล้องหน้า" else "สลับเลนส์: กล้องหลัง",
                        selected = true,
                        enabled = true,
                        onClick = onToggleCameraLens
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Primary Action 1: Record (or Stop if unlocked mid-session)
                Button(
                    onClick = {
                        if (isActive) {
                            onStopRecording()
                        } else if (!state.settings.hasPinConfigured) {
                            showPinSetupDialog = true
                        } else {
                            onStartRecording()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .testTag("primary_record_button"),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) CrimsonLive else EmeraldPulse,
                        contentColor = ObsidianBlack
                    )
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isActive) stringResource(R.string.action_stop_recording) else "บันทึก",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Primary Action 2: Sign Out
                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("sign_out_button"),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, VaultOutline)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.drive_sign_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!state.settings.hasPinConfigured) {
                    Text(
                        text = "กด \"บันทึก\" เพื่อตั้งรหัส PIN 4 หลักสำหรับปลดล็อกจอดำ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberMotion,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Bottom tiny 32GB free space status line
            Text(
                text = "พื้นที่เครื่องว่าง ${StorageStatsProvider.formatBytes(state.storageStats.freeBytes)} • ตัดรอบอัปโหลดและลบทิ้งอัตโนมัติ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
    }

    if (showPinSetupDialog) {
        PinSetupDialog(
            onDismiss = { showPinSetupDialog = false },
            onPinCreated = { newPin ->
                onSavePin(newPin)
                showPinSetupDialog = false
                onStartRecording()
            }
        )
    }
}

@Composable
private fun ModeMiniChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) CyanContainer else VaultSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) CyanDrive else VaultOutline),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) CyanDrive else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onPinCreated: (String) -> Unit
) {
    var draftPin by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            PinPadContent(
                title = stringResource(R.string.pin_setup_title),
                subtitle = stringResource(R.string.pin_setup_subtitle),
                enteredPin = draftPin,
                lockoutRemainingSec = 0L,
                errorMessage = null,
                onDigit = { digit ->
                    if (draftPin.length < 4) {
                        val next = draftPin + digit
                        draftPin = next
                        if (next.length == 4) {
                            onPinCreated(next)
                        }
                    }
                },
                onBackspace = {
                    if (draftPin.isNotEmpty()) {
                        draftPin = draftPin.dropLast(1)
                    }
                },
                secondaryActionLabel = "ยกเลิก",
                onSecondaryAction = onDismiss
            )
        }
    }
}
