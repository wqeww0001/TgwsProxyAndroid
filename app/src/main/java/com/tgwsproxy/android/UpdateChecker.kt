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

enum class AppChannel(val key: String, val ruTitle: String, val enTitle: String, val ruDescription: String, val enDescription: String) {
    Stable("stable", "Стабильная", "Stable", "Проверенные официальные релизы", "Tested official releases"),
    Beta("beta", "Бета / Снапшоты", "Beta & Snapshots", "Ранний доступ к новым функциям (возможны баги)", "Early access to new features (may contain bugs)"),
}

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val releaseNotes: String = "",
    val isPrerelease: Boolean = false,
)

data class ReleaseArchiveItem(
    val version: String,
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val publishedAt: String,
    val isPrerelease: Boolean,
    val apkUrl: String,
)

object UpdateChecker {
    const val DEFAULT_GITHUB_REPO = "wqeww0001/TgwsProxyAndroid"

    fun currentVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "0.0"
    }

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun checkLatest(repo: String, currentVersion: String, channel: AppChannel = AppChannel.Stable): UpdateInfo? {
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!cleanRepo.contains('/')) error("GitHub repo must look like owner/name")

        return runCatching {
            checkLatestViaApi(cleanRepo, currentVersion, channel)
        }.getOrElse { apiError ->
            runCatching {
                checkLatestViaReleaseRedirect(cleanRepo, currentVersion)
            }.getOrElse {
                throw IllegalStateException(apiError.message ?: apiError.javaClass.simpleName)
            }
        }
    }

    fun fetchAllReleases(repo: String = DEFAULT_GITHUB_REPO): List<ReleaseArchiveItem> {
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        return runCatching {
            val json = httpGet(
                url = "https://api.github.com/repos/$cleanRepo/releases?per_page=30",
                accept = "application/vnd.github+json",
            )
            val array = org.json.JSONArray(json)
            val list = mutableListOf<ReleaseArchiveItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tagName = obj.optString("tag_name").trim()
                val version = tagName.removePrefix("v").trim()
                val title = obj.optString("name", tagName)
                val body = obj.optString("body", "").trim()
                val publishedAt = obj.optString("published_at", "").take(10)
                val isPrerelease = obj.optBoolean("prerelease", false)
                var apkUrl = ""
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                            apkUrl = url
                            break
                        }
                    }
                }
                if (apkUrl.isBlank() && tagName.isNotBlank()) {
                    apkUrl = "https://github.com/$cleanRepo/releases/download/$tagName/TgwsProxyAndroid-$tagName.apk"
                }
                if (version.isNotBlank()) {
                    list.add(
                        ReleaseArchiveItem(
                            version = version,
                            tagName = tagName,
                            title = title,
                            releaseNotes = body,
                            publishedAt = publishedAt,
                            isPrerelease = isPrerelease,
                            apkUrl = apkUrl,
                        )
                    )
                }
            }
            list
        }.getOrThrow()
    }

    private fun checkLatestViaApi(repo: String, currentVersion: String, channel: AppChannel): UpdateInfo? {
        if (channel == AppChannel.Beta) {
            val json = httpGet(
                url = "https://api.github.com/repos/$repo/releases?per_page=5",
                accept = "application/vnd.github+json",
            )
            val array = org.json.JSONArray(json)
            if (array.length() > 0) {
                for (i in 0 until array.length()) {
                    val root = array.getJSONObject(i)
                    val latestVersion = root.optString("tag_name").trim().removePrefix("v")
                    val releaseNotes = root.optString("body").trim()
                    val isPrerelease = root.optBoolean("prerelease", false)
                    val assets = root.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val name = asset.optString("name")
                            val apkUrl = asset.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) && apkUrl.isNotBlank()) {
                                if (isNewer(latestVersion, currentVersion)) {
                                    return UpdateInfo(latestVersion, apkUrl, releaseNotes, isPrerelease)
                                }
                            }
                        }
                    }
                }
            }
        }

        val json = httpGet(
            url = "https://api.github.com/repos/$repo/releases/latest",
            accept = "application/vnd.github+json",
        )
        val root = JSONObject(json)
        val latestVersion = root.optString("tag_name").trim().removePrefix("v")
        val releaseNotes = root.optString("body").trim()
        val isPrerelease = root.optBoolean("prerelease", false)
        val assets = root.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val apkUrl = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && apkUrl.isNotBlank()) {
                return if (isNewer(latestVersion, currentVersion)) UpdateInfo(latestVersion, apkUrl, releaseNotes, isPrerelease) else null
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

    fun downloadReleaseApk(context: Context, release: ReleaseArchiveItem): File {
        return downloadApk(context, UpdateInfo(release.version, release.apkUrl, release.releaseNotes, release.isPrerelease))
    }

    fun downloadApk(context: Context, info: UpdateInfo): File {
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean up any stale APKs or partial downloads in cacheDir and cacheDir/updates
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                    f.delete()
                }
            }
            updatesDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                    f.delete()
                }
            }
        }
        val file = File(updatesDir, "tgwsproxyandroid-$safeVersion.apk")
        val partial = File(updatesDir, "${file.name}.part")
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

    const val PREF_REOPEN_AFTER_UPDATE = "reopen_after_update"
    const val PREF_WAS_RUNNING_BEFORE_UPDATE = "was_running_before_update"

    fun installApk(context: Context, file: File) {
        context.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_REOPEN_AFTER_UPDATE, true)
            .putBoolean(PREF_WAS_RUNNING_BEFORE_UPDATE, ProxyServiceStatus.isRunning)
            .apply()

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private data class ParsedSemVer(
        val core: List<Int>,
        val preReleaseTag: String,
        val preReleaseNum: Int,
    )

    private fun parseSemVer(raw: String): ParsedSemVer {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val dashIdx = cleaned.indexOf('-')
        val coreStr = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val preStr = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).lowercase() else ""
        val core = coreStr.split('.', '_').mapNotNull { it.toIntOrNull() }
        if (preStr.isEmpty()) {
            return ParsedSemVer(core = core, preReleaseTag = "", preReleaseNum = 0)
        }
        val preTokens = preStr.split('.', '-', '_')
        val tag = preTokens.firstOrNull { it.any(Char::isLetter) }.orEmpty()
        val num = preTokens.mapNotNull { it.toIntOrNull() }.firstOrNull() ?: 0
        return ParsedSemVer(core = core, preReleaseTag = tag, preReleaseNum = num)
    }

    private fun preReleaseRank(tag: String): Int = when {
        tag.isEmpty() -> 100 // Final release is newer than any pre-release of the same X.Y.Z
        tag.startsWith("rc") -> 30
        tag.startsWith("beta") || tag == "b" -> 20
        tag.startsWith("alpha") || tag == "a" -> 10
        else -> 5
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseSemVer(latest)
        val c = parseSemVer(current)
        val max = maxOf(l.core.size, c.core.size)
        for (i in 0 until max) {
            val left = l.core.getOrElse(i) { 0 }
            val right = c.core.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        val leftRank = preReleaseRank(l.preReleaseTag)
        val rightRank = preReleaseRank(c.preReleaseTag)
        if (leftRank != rightRank) return leftRank > rightRank
        if (l.preReleaseNum != c.preReleaseNum) return l.preReleaseNum > c.preReleaseNum
        return false
    }

    fun isOlder(candidate: String, current: String): Boolean = isNewer(current, candidate)

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
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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
