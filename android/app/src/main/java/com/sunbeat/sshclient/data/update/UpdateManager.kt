package com.sunbeat.sshclient.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val currentCode: Int,
    val currentName: String,
    val latestCode: Int = 0,
    val latestName: String = "",
    val apkUrl: String = "",
    val updateAvailable: Boolean = false,
    val error: String? = null,
)

class UpdateManager(private val context: Context) {
    private val currentCode: Int
        get() = context.packageManager
            .getPackageInfo(context.packageName, 0).versionCode

    private val currentName: String
        get() = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    companion object {
        private const val REMOTE_BASE = "https://www.sunbeatus.com"
        private const val VERSION_JSON_PATH = "/ssh_apk/version.json"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 10_000
    }

    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val url = URL("$REMOTE_BASE$VERSION_JSON_PATH")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext UpdateInfo(
                    currentCode = currentCode,
                    currentName = currentName,
                    error = "Server returned HTTP $code",
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val remote = JSONObject(body)
            val remoteCode = remote.optInt("versionCode", 0)
            val remoteApkUrl = remote.optString("apkUrl", "")
            UpdateInfo(
                currentCode = currentCode,
                currentName = currentName,
                latestCode = remoteCode,
                latestName = remote.optString("versionName", ""),
                apkUrl = remoteApkUrl,
                updateAvailable = remoteCode > currentCode,
            )
        } catch (e: Exception) {
            UpdateInfo(
                currentCode = currentCode,
                currentName = currentName,
                error = e.message ?: "Unknown error",
            )
        }
    }

    fun getApkFile(): File {
        val dir = File(context.cacheDir, "apk")
        dir.mkdirs()
        return File(dir, "update.apk")
    }

    suspend fun downloadAndInstall(apkUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apkUrl.isBlank()) return@withContext Result.failure(Exception("APK URL is empty"))

            val apkFile = getApkFile()
            apkFile.delete()

            val url = URL(apkUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext Result.failure(Exception("APK download failed: HTTP $code"))
            }

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(install)

            Result.success("Install started")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
