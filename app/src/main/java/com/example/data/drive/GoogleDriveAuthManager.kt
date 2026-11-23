package com.example.data.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DriveStorageQuota(
    val email: String?,
    val usedBytes: Long,
    val limitBytes: Long
)

sealed class DriveAuthOutcome {
    data class Authorized(
        val accessToken: String,
        val email: String?,
        val quota: DriveStorageQuota?
    ) : DriveAuthOutcome()

    data class ResolutionRequired(val pendingIntent: PendingIntent) : DriveAuthOutcome()
    data class Failed(val message: String) : DriveAuthOutcome()
}

class GoogleDriveAuthManager(private val context: Context) {

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope(SCOPE_DRIVE_FILE),
                    Scope(SCOPE_USERINFO_EMAIL)
                )
            )
            .build()
    }

    suspend fun requestAuthorization(): DriveAuthOutcome {
        return try {
            val result = awaitTask(
                Identity.getAuthorizationClient(context).authorize(buildRequest())
            )
            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null) {
                    DriveAuthOutcome.ResolutionRequired(pendingIntent)
                } else {
                    DriveAuthOutcome.Failed("ต้องยืนยันสิทธิ์ Google Drive แต่ไม่พบหน้าต่างขออนุญาต")
                }
            } else {
                val token = result.accessToken
                if (token.isNullOrBlank()) {
                    DriveAuthOutcome.Failed("ได้รับ Token ว่างจาก Google Play Services")
                } else {
                    val email = fetchUserEmail(token)
                    val quota = fetchDriveQuota(token, email)
                    DriveAuthOutcome.Authorized(
                        accessToken = token,
                        email = quota?.email ?: email,
                        quota = quota
                    )
                }
            }
        } catch (e: Exception) {
            DriveAuthOutcome.Failed(e.localizedMessage ?: "ไม่สามารถเชื่อมต่อ Google Drive ได้")
        }
    }

    suspend fun handleConsentResult(data: Intent?): DriveAuthOutcome {
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                DriveAuthOutcome.Failed("ยกเลิกการลงชื่อเข้าใช้หรือไม่ได้รับสิทธิ์ Google Drive")
            } else {
                val email = fetchUserEmail(token)
                val quota = fetchDriveQuota(token, email)
                DriveAuthOutcome.Authorized(
                    accessToken = token,
                    email = quota?.email ?: email,
                    quota = quota
                )
            }
        } catch (e: Exception) {
            DriveAuthOutcome.Failed(e.localizedMessage ?: "การลงชื่อเข้าใช้ Google Drive ล้มเหลว")
        }
    }

    suspend fun getAccessTokenSilently(): String? {
        return when (val outcome = requestAuthorization()) {
            is DriveAuthOutcome.Authorized -> outcome.accessToken
            else -> null
        }
    }

    private suspend fun fetchUserEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                JSONObject(body).optString("email").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private suspend fun fetchDriveQuota(accessToken: String, fallbackEmail: String?): DriveStorageQuota? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/about?fields=storageQuota,user")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    val userEmail = json.optJSONObject("user")?.optString("emailAddress")
                        ?.takeIf { it.isNotBlank() } ?: fallbackEmail
                    val quota = json.optJSONObject("storageQuota")
                    val used = quota?.optString("usage")?.toLongOrNull() ?: 0L
                    val limit = quota?.optString("limit")?.toLongOrNull() ?: 0L
                    DriveStorageQuota(
                        email = userEmail,
                        usedBytes = used,
                        limitBytes = limit
                    )
                }
            }.getOrNull()
        }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result)
            }.addOnFailureListener { exception ->
                if (cont.isActive) cont.resumeWithException(exception)
            }
        }
}
