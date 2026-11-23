package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.RecordingSegmentEntity
import com.example.data.local.SegmentStatus
import com.example.data.local.StorageStatsProvider
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberMotion
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanDrive
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.VaultOutline
import com.example.ui.theme.VaultSurface
import com.example.ui.theme.VaultSurfaceElevated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    state: VaultUiState,
    onRetrySegment: (RecordingSegmentEntity) -> Unit,
    onRetryAll: () -> Unit,
    onPurgeLocal: (RecordingSegmentEntity) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                QueueSummaryHeader(
                    uploadedCount = state.uploadedCount,
                    totalPurgedBytes = state.totalPurgedBytes,
                    hasCompleted = state.segments.any { it.status == SegmentStatus.UPLOADED_AND_DELETED },
                    hasPendingOrFailed = state.segments.any {
                        it.status == SegmentStatus.PENDING_UPLOAD || it.status == SegmentStatus.FAILED
                    },
                    onRetryAll = onRetryAll,
                    onClearCompleted = onClearCompleted
                )
            }

            if (state.segments.isEmpty()) {
                item {
                    EmptyQueueCard()
                }
            } else {
                items(
                    items = state.segments,
                    key = { it.id }
                ) { segment ->
                    SegmentQueueCard(
                        segment = segment,
                        onRetry = { onRetrySegment(segment) },
                        onPurgeLocal = { onPurgeLocal(segment) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun QueueSummaryHeader(
    uploadedCount: Int,
    totalPurgedBytes: Long,
    hasCompleted: Boolean,
    hasPendingOrFailed: Boolean,
    onRetryAll: () -> Unit,
    onClearCompleted: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = VaultSurface),
        border = BorderStroke(1.dp, VaultOutline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "ขึ้น Drive & ลบแล้ว",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$uploadedCount ไฟล์",
                        style = MaterialTheme.typography.headlineMedium,
                        color = EmeraldPulse
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "คืนพื้นที่ให้เครื่อง 32GB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = StorageStatsProvider.formatBytes(totalPurgedBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        color = CyanDrive
                    )
                }
            }

            if (hasPendingOrFailed || hasCompleted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasPendingOrFailed) {
                        Button(
                            onClick = onRetryAll,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("retry_all_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanDrive,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ซิงก์คิวที่ค้าง")
                        }
                    }
                    if (hasCompleted) {
                        OutlinedButton(
                            onClick = onClearCompleted,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("clear_history_button"),
                            border = BorderStroke(1.dp, VaultOutline)
                        ) {
                            Text("ล้างประวัติที่ลบแล้ว", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueCard() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = VaultSurface),
        border = BorderStroke(1.dp, VaultOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VideoFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = stringResource(R.string.queue_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.queue_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SegmentQueueCard(
    segment: RecordingSegmentEntity,
    onRetry: () -> Unit,
    onPurgeLocal: () -> Unit
) {
    val (statusText, statusColor, statusBg) = when (segment.status) {
        SegmentStatus.RECORDING -> Triple("กำลังบันทึก...", CrimsonLive, CrimsonContainer)
        SegmentStatus.PENDING_UPLOAD -> Triple("รออัปโหลดขึ้น Drive", AmberMotion, AmberContainer)
        SegmentStatus.UPLOADING -> Triple("กำลังอัปโหลด ${segment.uploadProgress}%", CyanDrive, CyanContainer)
        SegmentStatus.UPLOADED_AND_DELETED -> Triple("ขึ้น Drive และลบในเครื่องแล้ว", EmeraldPulse, EmeraldContainer)
        SegmentStatus.UPLOADED_KEPT -> Triple("ขึ้น Drive แล้ว (เก็บไฟล์ในเครื่อง)", CyanDrive, CyanContainer)
        SegmentStatus.FAILED -> Triple("อัปโหลดไม่สำเร็จ", CrimsonLive, CrimsonContainer)
    }

    val statusIcon = when (segment.status) {
        SegmentStatus.UPLOADED_AND_DELETED, SegmentStatus.UPLOADED_KEPT -> Icons.Default.CloudDone
        SegmentStatus.UPLOADING, SegmentStatus.PENDING_UPLOAD -> Icons.Default.CloudUpload
        SegmentStatus.FAILED -> Icons.Default.ErrorOutline
        SegmentStatus.RECORDING -> Icons.Default.VideoFile
    }

    val dateStr = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        .format(Date(segment.startedAtMillis))

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = VaultSurface),
        border = BorderStroke(1.dp, VaultOutline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = segment.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val modeLabel = when (segment.triggerMode) {
                    "MOTION_DETECT" -> "จับเคลื่อนไหว"
                    "CAMERA_BACKGROUND" -> "กล้องเงียบ CameraX"
                    else -> "รอบต่อเนื่อง"
                }
                Text(
                    text = "$dateStr • $modeLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${formatDurationHms(segment.durationMillis / 1000L)} • ${StorageStatsProvider.formatBytes(segment.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (segment.status == SegmentStatus.UPLOADING) {
                LinearProgressIndicator(
                    progress = { (segment.uploadProgress / 100f).coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = CyanDrive,
                    trackColor = VaultSurfaceElevated,
                    strokeCap = StrokeCap.Round
                )
            }

            if (!segment.driveFileId.isNullOrBlank()) {
                Text(
                    text = "Drive ID: ${segment.driveFileId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldPulse
                )
            }

            if (!segment.errorMessage.isNullOrBlank()) {
                Text(
                    text = segment.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrimsonLive
                )
            }

            if (segment.status == SegmentStatus.FAILED ||
                segment.status == SegmentStatus.PENDING_UPLOAD ||
                (!segment.localDeleted && segment.status != SegmentStatus.RECORDING)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (segment.status == SegmentStatus.FAILED || segment.status == SegmentStatus.PENDING_UPLOAD) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("retry_segment_${segment.id}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_retry_upload))
                        }
                    }
                    if (!segment.localDeleted && segment.status != SegmentStatus.RECORDING) {
                        TextButton(
                            onClick = onPurgeLocal,
                            modifier = Modifier.testTag("purge_segment_${segment.id}")
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = CrimsonLive,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_purge_now), color = CrimsonLive)
                        }
                    }
                }
            }
        }
    }
}
