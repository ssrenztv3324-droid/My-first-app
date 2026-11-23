package com.example.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.R
import com.example.ui.theme.AmberMotion
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.VaultOutline
import com.example.ui.theme.VaultSurface
import com.example.ui.theme.VaultSurfaceElevated
import kotlinx.coroutines.delay

@Composable
fun BlackoutOverlay(
    hasPinConfigured: Boolean,
    lockoutRemainingSec: Long,
    pinErrorMessage: String?,
    onAttemptPinUnlock: (String) -> Boolean,
    onClearPinError: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Block hardware/gesture back button completely during pitch-black mode
    BackHandler(enabled = true) { }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window.attributes
            params.screenBrightness = 0.001f
            window.attributes = params

            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val params = window.attributes
                params.screenBrightness = originalBrightness
                window.attributes = params

                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var isPressing by remember { mutableStateOf(false) }
    var showPinPad by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }

    // Silent 2-second press-and-hold anywhere on the pure black screen reveals the PIN pad
    LaunchedEffect(isPressing, showPinPad) {
        if (isPressing && !showPinPad) {
            delay(1800L)
            if (isPressing) {
                if (!hasPinConfigured) {
                    onAttemptPinUnlock("")
                } else {
                    enteredPin = ""
                    onClearPinError()
                    showPinPad = true
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .testTag("blackout_overlay")
            .pointerInput(showPinPad) {
                if (!showPinPad) {
                    detectTapGestures(
                        onPress = {
                            isPressing = true
                            tryAwaitRelease()
                            isPressing = false
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // When showPinPad == false, ZERO UI elements are drawn: 100% pure #000000 OLED pitch black!
        if (showPinPad) {
            PinPadContent(
                title = stringResource(R.string.pin_unlock_title),
                subtitle = null,
                enteredPin = enteredPin,
                lockoutRemainingSec = lockoutRemainingSec,
                errorMessage = pinErrorMessage,
                onDigit = { digit ->
                    if (lockoutRemainingSec <= 0L && enteredPin.length < 4) {
                        val next = enteredPin + digit
                        enteredPin = next
                        if (next.length == 4) {
                            val unlocked = onAttemptPinUnlock(next)
                            if (!unlocked) {
                                enteredPin = ""
                            }
                        }
                    }
                },
                onBackspace = {
                    if (enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                    }
                },
                secondaryActionLabel = "กลับหน้าจอดำ",
                onSecondaryAction = {
                    enteredPin = ""
                    onClearPinError()
                    showPinPad = false
                }
            )
        }
    }
}

@Composable
fun PinPadContent(
    title: String,
    subtitle: String?,
    enteredPin: String,
    lockoutRemainingSec: Long,
    errorMessage: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    secondaryActionLabel: String,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLockedOut = lockoutRemainingSec > 0L

    Surface(
        shape = MaterialTheme.shapes.large,
        color = VaultSurface,
        border = BorderStroke(1.dp, VaultOutline),
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = if (isLockedOut) CrimsonLive else EmeraldPulse,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // 4-dot PIN display
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                repeat(4) { index ->
                    val filled = index < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isLockedOut -> CrimsonLive.copy(alpha = 0.25f)
                                    filled -> EmeraldPulse
                                    else -> VaultSurfaceElevated
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (filled) EmeraldPulse else VaultOutline,
                                shape = CircleShape
                            )
                    )
                }
            }

            if (isLockedOut) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CrimsonContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ใส่รหัสผิดเกินกำหนด\nกรุณารอ ${formatMmSs(lockoutRemainingSec)} ก่อนลองใหม่",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrimsonLive,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(10.dp)
                            .testTag("pin_lockout_banner")
                    )
                }
            } else if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberMotion,
                    textAlign = TextAlign.Center
                )
            }

            // Numeric Keypad
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9")
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { rowDigits ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowDigits.forEach { digit ->
                            PinKeyButton(
                                label = digit,
                                enabled = !isLockedOut,
                                onClick = { onDigit(digit) }
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.size(64.dp))
                    PinKeyButton(
                        label = "0",
                        enabled = !isLockedOut,
                        onClick = { onDigit("0") }
                    )
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(VaultSurfaceElevated)
                            .clickable(enabled = !isLockedOut, onClick = onBackspace)
                            .testTag("pin_backspace"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "ลบตัวเลข",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onSecondaryAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("pin_secondary_action"),
                border = BorderStroke(1.dp, VaultOutline)
            ) {
                Text(
                    text = secondaryActionLabel,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PinKeyButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (enabled) VaultSurfaceElevated else VaultSurfaceElevated.copy(alpha = 0.4f))
            .border(1.dp, VaultOutline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("pin_key_$label"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

fun formatDurationHms(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600L
    val mins = (totalSeconds % 3600L) / 60L
    val secs = totalSeconds % 60L
    return String.format("%02d:%02d:%02d", hrs, mins, secs)
}

private fun formatMmSs(totalSeconds: Long): String {
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return String.format("%02d:%02d", mins, secs)
}
