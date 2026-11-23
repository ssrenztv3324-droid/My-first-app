package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.VideoQualityPreset
import com.example.ui.theme.AmberMotion
import com.example.ui.theme.CyanDrive
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.VaultOutline
import com.example.ui.theme.VaultSurface
import com.example.ui.theme.VaultSurfaceElevated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: VaultUiState,
    onCycleMinutesChange: (Int) -> Unit,
    onQualityChange: (VideoQualityPreset) -> Unit,
    onAutoDeleteChange: (Boolean) -> Unit,
    onAutoBlackoutDelayChange: (Int) -> Unit,
    onMotionSensitivityChange: (Int) -> Unit,
    onMotionClipDurationChange: (Int) -> Unit,
    onMinReserveMbChange: (Int) -> Unit,
    onStealthTitleChange: (String) -> Unit,
    onSavePin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    var notifTitleDraft by remember(settings.stealthNotificationTitle) {
        mutableStateOf(settings.stealthNotificationTitle)
    }
    var showChangePinDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Cycle Rotation & Immediate Purge
            item {
                SettingsSectionCard(title = "รอบการตัดไฟล์ & ลบอัตโนมัติ (32GB)") {
                    Text(
                        text = "ตัดรอบอัปโหลดทุกๆ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            5 to "5 นาที (ทดสอบ)",
                            30 to "30 นาที",
                            60 to "1 ชั่วโมง",
                            120 to "2 ชั่วโมง (แนะนำ)"
                        ).forEach { (mins, label) ->
                            VaultFilterChip(
                                selected = settings.cycleMinutes == mins,
                                label = label,
                                onClick = { onCycleMinutesChange(mins) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ลบไฟล์ในเครื่องทันทีหลังอัปโหลดสำเร็จ",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "ลบเฉพาะเมื่อ Google Drive ยืนยันรับไฟล์แล้ว",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoDeleteAfterUpload,
                            onCheckedChange = onAutoDeleteChange,
                            modifier = Modifier.testTag("auto_delete_switch"),
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldPulse)
                        )
                    }

                    Text(
                        text = "ตัดรอบฉุกเฉินเมื่อพื้นที่ว่างต่ำกว่า",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            512 to "512 MB",
                            1024 to "1 GB (แนะนำ)",
                            2048 to "2 GB"
                        ).forEach { (mb, label) ->
                            VaultFilterChip(
                                selected = settings.minFreeStorageReserveMb == mb,
                                label = label,
                                onClick = { onMinReserveMbChange(mb) }
                            )
                        }
                    }
                }
            }

            // 2. Video Quality
            item {
                SettingsSectionCard(title = "ความละเอียดวิดีโอ (ประหยัดพื้นที่ 32GB)") {
                    VideoQualityPreset.entries.forEach { preset ->
                        val selected = settings.videoQuality == preset
                        FilterChip(
                            selected = selected,
                            onClick = { onQualityChange(preset) },
                            label = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(preset.labelTh, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${preset.shortSpec} • ~${preset.approxMbPer2Hours} MB / 2 ชม.",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EmeraldContainer,
                                selectedLabelColor = EmeraldPulse
                            )
                        )
                    }
                }
            }

            // 3. OLED Blackout & Motion Mode
            item {
                SettingsSectionCard(title = "โหมดดับหน้าจอ & จับความเคลื่อนไหว") {
                    Text(
                        text = "เข้าโหมดดับหน้าจอ OLED อัตโนมัติหลังเริ่มอัด",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to "ทันที (ดำสนิท)",
                            3 to "3 วินาที",
                            10 to "10 วินาที"
                        ).forEach { (sec, label) ->
                            VaultFilterChip(
                                selected = settings.autoBlackoutDelaySec == sec,
                                label = label,
                                onClick = { onAutoBlackoutDelayChange(sec) }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showChangePinDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("change_pin_button"),
                        border = BorderStroke(1.dp, VaultOutline)
                    ) {
                        Text(
                            text = if (settings.hasPinConfigured) "เปลี่ยนรหัส PIN ปลดล็อกจอดำ" else "ตั้งรหัส PIN ปลดล็อกจอดำ",
                            color = EmeraldPulse
                        )
                    }

                    Text(
                        text = "ความไวในการตรวจจับความเคลื่อนไหว",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            1 to "ต่ำ (6%)",
                            2 to "กลาง (2.5%)",
                            3 to "สูง (0.8%)"
                        ).forEach { (level, label) ->
                            VaultFilterChip(
                                selected = settings.motionSensitivity == level,
                                label = label,
                                onClick = { onMotionSensitivityChange(level) }
                            )
                        }
                    }

                    Text(
                        text = "ความยาวคลิปต่อการเคลื่อนไหว 1 ครั้ง",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            30 to "30 วินาที",
                            60 to "1 นาที",
                            180 to "3 นาที"
                        ).forEach { (sec, label) ->
                            VaultFilterChip(
                                selected = settings.motionClipDurationSec == sec,
                                label = label,
                                onClick = { onMotionClipDurationChange(sec) }
                            )
                        }
                    }
                }
            }

            // 4. Stealth Notification Label & Android OS Constraints
            item {
                SettingsSectionCard(title = "การซ่อนแจ้งเตือน & ข้อจำกัด Android") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = CyanDrive,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ช่องแจ้งเตือนระดับเงียบสุด (MIN / ไม่มีเสียง / ไม่เด้ง)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyanDrive
                        )
                    }

                    OutlinedTextField(
                        value = notifTitleDraft,
                        onValueChange = {
                            notifTitleDraft = it
                            onStealthTitleChange(it)
                        },
                        label = { Text("ข้อความไอคอนระบบ (แบบพราง)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stealth_title_input")
                    )

                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = AmberMotion,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.os_notice_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AmberMotion
                            )
                            Text(
                                text = stringResource(R.string.os_notice_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }

    if (showChangePinDialog) {
        PinSetupDialog(
            onDismiss = { showChangePinDialog = false },
            onPinCreated = { newPin ->
                onSavePin(newPin)
                showChangePinDialog = false
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = VaultSurface),
        border = BorderStroke(1.dp, VaultOutline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun VaultFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = VaultSurfaceElevated,
            selectedContainerColor = EmeraldContainer,
            selectedLabelColor = EmeraldPulse
        )
    )
}
