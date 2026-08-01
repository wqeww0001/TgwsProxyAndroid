package com.tgwsproxy.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        val partial = File(context.cacheDir, "${file.name}.part")
        partial.delete()
        val requestedUrl = URL(info.apkUrl)
        require(isTrustedGithubHost(requestedUrl.host)) { "Untrusted update host" }
        (requestedUrl.openConnection() as HttpURLConnection).run {
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
            require(isTrustedGithubHost(url.host)) { "Untrusted update redirect" }
            val declaredLength = contentLengthLong
            require(declaredLength == -1L || declaredLength in 1..MAX_APK_BYTES) { "Invalid APK size: $declaredLength" }
            inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_APK_BYTES) { "APK is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            disconnect()
        }
        verifyDownloadedApk(context, partial)
        if (file.exists() && !file.delete()) error("Cannot replace cached APK")
        if (!partial.renameTo(file)) error("Cannot finalize APK download")
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

    internal fun isNewerForTest(latest: String, current: String): Boolean = isNewer(latest, current)

    private fun verifyDownloadedApk(context: Context, file: File) {
        require(file.length() > MIN_APK_BYTES) { "Downloaded APK is empty" }
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == magic.size && magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                "Downloaded file is not an APK"
            }
        }

        val archive = packageArchiveInfo(context.packageManager, file.absolutePath)
            ?: error("Cannot read downloaded APK metadata")
        require(archive.packageName == context.packageName) { "Update package name mismatch" }

        val installed = context.packageManager.getPackageInfoCompat(context.packageName)
        val expected = installed.signingDigests()
        val actual = archive.signingDigests()
        require(expected.isNotEmpty() && actual.isNotEmpty() && expected.intersect(actual).isNotEmpty()) {
            "Update signing certificate mismatch"
        }
    }

    private fun packageArchiveInfo(packageManager: PackageManager, path: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun PackageInfo.signingDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            this.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun isTrustedGithubHost(host: String): Boolean {
        val clean = host.lowercase()
        return clean == "github.com" || clean == "api.github.com" || clean.endsWith(".githubusercontent.com")
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

    private const val MIN_APK_BYTES = 64 * 1024L
    private const val MAX_APK_BYTES = 200 * 1024 * 1024L
}
