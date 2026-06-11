package com.tgwsproxy.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
)

object UpdateChecker {
    const val DEFAULT_GITHUB_REPO = "wqeww0001/TgwsProxyAndroid"

    fun currentVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "0.0"
    }

    fun checkLatest(repo: String, currentVersion: String): UpdateInfo? {
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!cleanRepo.contains('/')) error("GitHub repo must look like owner/name")

        return runCatching {
            checkLatestViaApi(cleanRepo, currentVersion)
        }.getOrElse { apiError ->
            runCatching {
                checkLatestViaReleaseRedirect(cleanRepo, currentVersion)
            }.getOrElse {
                throw IllegalStateException(apiError.message ?: apiError.javaClass.simpleName)
            }
        }
    }

    private fun checkLatestViaApi(repo: String, currentVersion: String): UpdateInfo? {
        val json = httpGet(
            url = "https://api.github.com/repos/$repo/releases/latest",
            accept = "application/vnd.github+json",
        )
        val root = JSONObject(json)
        val latestVersion = root.optString("tag_name").trim().removePrefix("v")
        val assets = root.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val apkUrl = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl.isNotBlank()) {
                return if (isNewer(latestVersion, currentVersion)) UpdateInfo(latestVersion, apkUrl) else null
            }
        }
        error("Latest GitHub release has no APK asset")
    }

    private fun checkLatestViaReleaseRedirect(repo: String, currentVersion: String): UpdateInfo? {
        val url = URL("https://github.com/$repo/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "TgwsProxyAndroid")
        }
        val code = connection.responseCode
        val location = connection.getHeaderField("Location").orEmpty()
        connection.disconnect()
        if (code !in 300..399 || location.isBlank()) error("GitHub latest redirect failed: HTTP $code")

        val latestVersion = location.substringAfterLast('/').removePrefix("v").trim()
        if (latestVersion.isBlank()) error("GitHub latest tag not found")
        val apkUrl = "https://github.com/$repo/releases/download/v$latestVersion/TgwsProxyAndroid-v$latestVersion-release.apk"
        return if (isNewer(latestVersion, currentVersion)) UpdateInfo(latestVersion, apkUrl) else null
    }

    fun downloadApk(context: Context, info: UpdateInfo): File {
        val file = File(context.cacheDir, "tgwsproxyandroid-${info.version}.apk")
        (URL(info.apkUrl).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "TgwsProxyAndroid")
            val code = responseCode
            if (code !in 200..299) {
                val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                disconnect()
                error("APK download failed: HTTP $code ${body.take(120)}")
            }
            inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            disconnect()
        }
        return file
    }

    fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val max = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until max) {
            val left = latestParts.getOrElse(i) { 0 }
            val right = currentParts.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return latest != current
    }

    private fun httpGet(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "TgwsProxyAndroid")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) error("GitHub API failed: HTTP $code ${body.take(120)}")
        return body
    }
}
