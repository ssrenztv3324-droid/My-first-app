package com.example.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DriveUploadResult(
    val fileId: String,
    val folderId: String,
    val verifiedBytes: Long
)

class GoogleDriveUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mp4MediaType = "video/mp4".toMediaType()

    suspend fun uploadMp4(
        accessToken: String,
        file: File,
        cachedFolderId: String?,
        folderName: String = "VaultRec_Recordings",
        onProgress: (Int) -> Unit
    ): Result<DriveUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists() && file.length() > 0L) {
                "ไม่พบไฟล์วิดีโอในเครื่อง (${file.name})"
            }

            val folderId = resolveOrCreateFolder(
                accessToken = accessToken,
                cachedFolderId = cachedFolderId,
                folderName = folderName
            )

            val sessionUri = startResumableUploadSession(
                accessToken = accessToken,
                fileName = file.name,
                fileSize = file.length(),
                parentFolderId = folderId
            )

            val uploadedJson = streamFileToSession(
                sessionUri = sessionUri,
                file = file,
                onProgress = onProgress
            )

            val fileId = uploadedJson.optString("id")
            val verifiedSize = uploadedJson.optString("size").toLongOrNull() ?: file.length()
            require(fileId.isNotBlank()) {
                "Google Drive ไม่ได้ส่ง File ID กลับมา"
            }

            DriveUploadResult(
                fileId = fileId,
                folderId = folderId,
                verifiedBytes = verifiedSize
            )
        }
    }

    private fun resolveOrCreateFolder(
        accessToken: String,
        cachedFolderId: String?,
        folderName: String
    ): String {
        if (!cachedFolderId.isNullOrBlank()) {
            val verifyReq = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$cachedFolderId?fields=id,trashed")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(verifyReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    if (!obj.optBoolean("trashed", false) && obj.optString("id").isNotBlank()) {
                        return cachedFolderId
                    }
                }
            }
        }

        val query = "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name)&spaces=drive")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        client.newCall(searchReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val files = JSONObject(body).optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val existingId = files.getJSONObject(0).optString("id")
                    if (existingId.isNotBlank()) return existingId
                }
            }
        }

        val createMetadata = JSONObject().apply {
            put("name", folderName)
            put("mimeType", "application/vnd.google-apps.folder")
        }.toString().toRequestBody(jsonMediaType)

        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(createMetadata)
            .build()

        client.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("สร้างโฟลเดอร์ Google Drive ไม่สำเร็จ (${resp.code}): $body")
            }
            val newId = JSONObject(body).optString("id")
            require(newId.isNotBlank()) { "ไม่ได้รับ Folder ID จาก Google Drive" }
            return newId
        }
    }

    private fun startResumableUploadSession(
        accessToken: String,
        fileName: String,
        fileSize: Long,
        parentFolderId: String
    ): String {
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("mimeType", "video/mp4")
            put("parents", org.json.JSONArray().put(parentFolderId))
        }.toString().toRequestBody(jsonMediaType)

        val initReq = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("X-Upload-Content-Type", "video/mp4")
            .addHeader("X-Upload-Content-Length", fileSize.toString())
            .post(metadata)
            .build()

        client.newCall(initReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("เริ่มอัปโหลด Drive ไม่สำเร็จ (${resp.code}): $err")
            }
            return resp.header("Location")
                ?: error("ไม่พบ Upload Session URL จาก Google Drive")
        }
    }

    private fun streamFileToSession(
        sessionUri: String,
        file: File,
        onProgress: (Int) -> Unit
    ): JSONObject {
        val totalBytes = file.length().coerceAtLeast(1L)
        val streamingBody = object : RequestBody() {
            override fun contentType() = mp4MediaType
            override fun contentLength(): Long = file.length()

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var uploaded = 0L
                var lastPct = -1
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        val pct = ((uploaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }

        val uploadReq = Request.Builder()
            .url(sessionUri)
            .put(streamingBody)
            .build()

        client.newCall(uploadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("อัปโหลดไฟล์ขึ้น Drive ล้มเหลว (${resp.code}): $body")
            }
            onProgress(100)
            return JSONObject(body)
        }
    }
}
