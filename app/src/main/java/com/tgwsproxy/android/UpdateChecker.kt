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

        val url = URL("https://api.github.com/repos/$cleanRepo/releases/latest")
        val json = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TgwsProxyAndroid")
            inputStream.bufferedReader().use { it.readText() }
        }
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

    fun downloadApk(context: Context, info: UpdateInfo): File {
        val file = File(context.cacheDir, "tgwsproxyandroid-${info.version}.apk")
        (URL(info.apkUrl).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "TgwsProxyAndroid")
            inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
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
}
