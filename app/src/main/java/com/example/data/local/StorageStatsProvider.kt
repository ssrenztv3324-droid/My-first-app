package com.example.data.local

import android.content.Context
import android.os.StatFs
import java.io.File
import java.util.Locale

data class StorageStats(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedByVaultBytes: Long,
    val pendingFilesCount: Int
) {
    val freeMb: Long get() = freeBytes / (1024L * 1024L)
    val usedPercent: Float
        get() = if (totalBytes > 0L) ((totalBytes - freeBytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object StorageStatsProvider {
    fun getRecordingsDir(context: Context): File {
        val dir = File(context.filesDir, "vault_segments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun readStats(context: Context): StorageStats {
        val dir = getRecordingsDir(context)
        val statFs = StatFs(dir.absolutePath)
        val total = statFs.totalBytes
        val free = statFs.availableBytes
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val vaultBytes = files.sumOf { it.length() }
        return StorageStats(
            totalBytes = total,
            freeBytes = free,
            usedByVaultBytes = vaultBytes,
            pendingFilesCount = files.size
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
