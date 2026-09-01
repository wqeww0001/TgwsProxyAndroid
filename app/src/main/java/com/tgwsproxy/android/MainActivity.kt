package com.tgwsproxy.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import com.tgwsproxy.android.benchmark.DomainBenchmark
import com.tgwsproxy.android.benchmark.DomainPingResult
import com.tgwsproxy.android.AppChannel
import com.tgwsproxy.android.ReleaseArchiveItem
import com.tgwsproxy.android.config.ProxyProfile
import com.tgwsproxy.android.proxy.ProxyLogger
import com.tgwsproxy.android.traffic.TrafficStatsManager
import com.tgwsproxy.android.traffic.TrafficSummary
import com.tgwsproxy.android.ui.theme.*
import com.tgwsproxy.android.util.NetworkUtils
import com.tgwsproxy.android.util.QrGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        requestStoragePermission()
        setContent {
            val context = LocalContext.current
            var themeMode by rememberSaveable {
                mutableStateOf(AppThemeMode.from(context.getProxyPref(THEME_PREF, AppThemeMode.Light.name)))
            }
            TgwsProxyAndroidTheme(darkTheme = themeMode == AppThemeMode.Dark) {
                ProxyScreen(
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        context.saveProxyPref(THEME_PREF, it.name)
                    },
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 11)
    }
}

data class ProxyStatus(
    val isRunning: Boolean = false,
    val uptime: String = "00:00",
    val localPing: Long = -1,
    val isStarting: Boolean = false,
)

private enum class AppLanguage(val code: String) {
    Ru("ru"),
    En("en"),
}

private enum class AppThemeMode {
    Light,
    Dark,
    Aurora,
    Sunset;

    companion object {
        fun from(value: String): AppThemeMode = entries.firstOrNull { it.name == value } ?: Light
    }
}

private enum class UpdateIntervalUnit(val minutes: Long) {
    Minutes(1),
    Hours(60),
    Days(24 * 60),
}

private enum class LogCategory { General, Errors }

private data class TelegramClient(val packageName: String, val label: String)

private enum class AppTab {
    Home,
    Settings,
    Logs,
    Help,
}

private data class LatestStats(
    val active: String = "0",
    val cf: String = "0",
    val ws: String = "0",
    val tcp: String = "0",
    val errors: String = "0",
    val up: String = "0B",
    val down: String = "0B",
)

private data class UiStrings(
    val menu: String,
    val language: String,
    val russian: String,
    val english: String,
    val active: String,
    val stopped: String,
    val uptime: String,
    val service: String,
    val online: String,
    val localEndpoint: String,
    val secret: String,
    val currentLink: String,
    val proxyOptions: String,
    val fakeTlsDomain: String,
    val emptyDdSecret: String,
    val cfWorkerDomain: String,
    val emptyDirectFallback: String,
    val cloudflareCdn: String,
    val wsPool: String,
    val wsPoolHint: String,
    val updateCheck: String,
    val requiredUpdate: String,
    val working: String,
    val installRequiredUpdate: String,
    val checkForUpdate: String,
    val debugLog: String,
    val noEventsYet: String,
    val copyTelegramLink: String,
    val openInTelegram: String,
    val start: String,
    val stop: String,
    val linkCopied: String,
    val downloadLogs: String,
    val logsSaved: String,
    val logsSaveFailed: String,
    val currentVersion: String,
    val updateCheckFailed: String,
    val installToContinue: String,
    val downloadingRequiredUpdate: String,
    val checkingGithub: String,
    val noUpdateFound: String,
    val downloading: String,
    val installerOpened: String,
    val updateFailed: String,
    val autoUpdates: String,
    val autoUpdatesEnabled: String,
    val autoUpdatesWarning: String,
    val batteryProtection: String,
    val batteryRestricted: String,
    val batteryUnrestricted: String,
    val allowBackgroundWork: String,
    val disableAnyway: String,
    val keepEnabled: String,
    val updateInterval: String,
    val minutesShort: String,
    val hoursShort: String,
    val splashSubtitle: String,
)

private fun strings(language: AppLanguage): UiStrings = when (language) {
    AppLanguage.Ru -> UiStrings(
        menu = "Меню",
        language = "Язык",
        russian = "Русский",
        english = "English",
        active = "Работает",
        stopped = "Остановлен",
        uptime = "Аптайм",
        service = "Сервис",
        online = "Онлайн",
        localEndpoint = "Локальный адрес",
        secret = "Секрет",
        currentLink = "Текущая ссылка",
        proxyOptions = "Настройки прокси",
        fakeTlsDomain = "Fake TLS домен",
        emptyDdSecret = "пусто = dd secret",
        cfWorkerDomain = "Cloudflare домен",
        emptyDirectFallback = "пусто = авто CF + TCP fallback",
        cloudflareCdn = "Cloudflare CDN",
        wsPool = "Пул быстрых соединений",
        wsPoolHint = "Больше соединений — ниже задержка, но выше расход батареи",
        updateCheck = "Проверка обновлений",
        requiredUpdate = "Доступно обновление",
        working = "Работаю...",
        installRequiredUpdate = "Скачать и установить",
        checkForUpdate = "Проверить обновления",
        debugLog = "Лог отладки",
        noEventsYet = "Пока нет событий",
        copyTelegramLink = "Скопировать ссылку Telegram",
        openInTelegram = "Открыть в Telegram",
        start = "Запустить",
        stop = "Остановить",
        linkCopied = "Ссылка скопирована",
        downloadLogs = "Скачать логи",
        logsSaved = "Логи сохранены в Загрузки",
        logsSaveFailed = "Не удалось сохранить логи",
        currentVersion = "Текущая версия",
        updateCheckFailed = "Не удалось проверить обновления. Текущая версия",
        installToContinue = "Установи его, чтобы продолжить.",
        downloadingRequiredUpdate = "Скачиваю обязательное обновление...",
        checkingGithub = "Проверяю GitHub Releases...",
        noUpdateFound = "Обновлений нет. Текущая версия",
        downloading = "Скачиваю",
        installerOpened = "Открыт установщик версии",
        updateFailed = "Ошибка обновления",
        autoUpdates = "Автообновления",
        autoUpdatesEnabled = "Автопроверка включена",
        autoUpdatesWarning = "Автопроверка отключена. Прокси продолжит работать, обновления можно проверять вручную.",
        batteryProtection = "Работа в фоне",
        batteryRestricted = "Система может принудительно закрыть прокси",
        batteryUnrestricted = "Ограничения батареи отключены",
        allowBackgroundWork = "Разрешить постоянную работу",
        disableAnyway = "Отключить",
        keepEnabled = "Оставить",
        updateInterval = "Интервал проверки",
        minutesShort = "мин",
        hoursShort = "ч",
        splashSubtitle = "Rust core, Cloudflare fallback",
    )
    AppLanguage.En -> UiStrings(
        menu = "Menu",
        language = "Language",
        russian = "Русский",
        english = "English",
        active = "Active",
        stopped = "Stopped",
        uptime = "Uptime",
        service = "Service",
        online = "Online",
        localEndpoint = "Local endpoint",
        secret = "Secret",
        currentLink = "Current link",
        proxyOptions = "Proxy options",
        fakeTlsDomain = "Fake TLS domain",
        emptyDdSecret = "empty = dd secret",
        cfWorkerDomain = "Cloudflare domain",
        emptyDirectFallback = "empty = auto CF + TCP fallback",
        cloudflareCdn = "Cloudflare CDN",
        wsPool = "Fast connection pool",
        wsPoolHint = "More connections reduce latency but use more battery",
        updateCheck = "Update check",
        requiredUpdate = "Update available",
        working = "Working...",
        installRequiredUpdate = "Download and install",
        checkForUpdate = "Check for update",
        debugLog = "Debug log",
        noEventsYet = "No events yet",
        copyTelegramLink = "Copy Telegram link",
        openInTelegram = "Open in Telegram",
        start = "Start",
        stop = "Stop",
        linkCopied = "Link copied",
        downloadLogs = "Download logs",
        logsSaved = "Logs saved to Downloads",
        logsSaveFailed = "Failed to save logs",
        currentVersion = "Current version",
        updateCheckFailed = "Update check failed. Current version",
        installToContinue = "Install it to continue.",
        downloadingRequiredUpdate = "Downloading required update...",
        checkingGithub = "Checking GitHub Releases...",
        noUpdateFound = "No update found. Current version",
        downloading = "Downloading",
        installerOpened = "Installer opened for version",
        updateFailed = "Update failed",
        autoUpdates = "Auto updates",
        autoUpdatesEnabled = "Auto check enabled",
        autoUpdatesWarning = "Auto check is disabled. The proxy will keep working and updates can be checked manually.",
        batteryProtection = "Background operation",
        batteryRestricted = "The system may force-stop the proxy",
        batteryUnrestricted = "Battery restrictions are disabled",
        allowBackgroundWork = "Allow continuous operation",
        disableAnyway = "Disable",
        keepEnabled = "Keep enabled",
        updateInterval = "Check interval",
        minutesShort = "min",
        hoursShort = "h",
        splashSubtitle = "Rust core, Cloudflare fallback",
    )
}

@Composable
private fun ProxyScreen(
    themeMode: AppThemeMode = AppThemeMode.Light,
    onThemeModeChange: (AppThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var language by rememberSaveable {
        mutableStateOf(
            if (context.getProxyPref(LANGUAGE_PREF, AppLanguage.Ru.code) == AppLanguage.En.code) AppLanguage.En else AppLanguage.Ru,
        )
    }
    val text = strings(language)
    var proxyStatus by remember { mutableStateOf(ProxyStatus()) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    var secret by remember { mutableStateOf("") }
    var fakeTlsDomain by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "")) }
    var cfWorkerDomain by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, ProxyConfig.DEFAULT_CF_WORKER_DOMAIN)) }
    var cfEnabled by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_CF_ENABLED, true)) }
    var dcMappings by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_DC_IPS, "")) }
    var poolSize by rememberSaveable {
        mutableIntStateOf(
            context.getProxyPref(ProxyService.EXTRA_POOL_SIZE, "4").toIntOrNull()?.takeIf { it in listOf(2, 4, 6) } ?: 4,
        )
    }
    var updateMessage by remember(language) { mutableStateOf("${text.currentVersion}: ${UpdateChecker.currentVersion(context)}") }
    var updateBusy by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var autoUpdateEnabled by rememberSaveable { mutableStateOf(context.getProxyPref(AUTO_UPDATE_ENABLED_PREF, true)) }
    var autoUpdateValue by rememberSaveable {
        mutableIntStateOf(context.getProxyPref(AUTO_UPDATE_VALUE_PREF, "1").toIntOrNull()?.coerceIn(1, 999) ?: 1)
    }
    var autoUpdateUnit by rememberSaveable {
        mutableStateOf(
            UpdateIntervalUnit.entries.firstOrNull {
                it.name == context.getProxyPref(AUTO_UPDATE_UNIT_PREF, UpdateIntervalUnit.Hours.name)
            } ?: UpdateIntervalUnit.Hours,
        )
    }
    var autoStartProxy by rememberSaveable { mutableStateOf(context.getProxyPref(AUTO_START_PROXY_PREF, false)) }
    var allowLan by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_ALLOW_LAN, false)) }
    var smartStandby by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_SMART_STANDBY, true)) }
    var appChannel by rememberSaveable {
        mutableStateOf(
            AppChannel.entries.firstOrNull {
                it.key == context.getProxyPref("app_channel", AppChannel.Stable.key)
            } ?: AppChannel.Stable
        )
    }
    var showBetaWarningDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveReleases by remember { mutableStateOf<List<ReleaseArchiveItem>>(emptyList()) }
    var isArchiveLoading by remember { mutableStateOf(false) }
    var rollbackConfirmRelease by remember { mutableStateOf<ReleaseArchiveItem?>(null) }
    var isRollbackDownloading by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var trafficSummary by remember { mutableStateOf(TrafficStatsManager.getSummary(context)) }
    var telegramClients by remember { mutableStateOf(emptyList<TelegramClient>()) }
    var showTelegramClientDialog by remember { mutableStateOf(false) }
    var showDisableAutoUpdateWarning by rememberSaveable { mutableStateOf(false) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    val link = remember(secret, fakeTlsDomain) { ProxyConfig.telegramProxyLink(secret, ProxyConfig.normalizeDomain(fakeTlsDomain)) }

    fun runUpdateCheck(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (manual) updateMessage = text.checkingGithub
        scope.launch {
            val result = runCatching {
                val current = UpdateChecker.currentVersion(context)
                val update = withContext(Dispatchers.IO) {
                    UpdateChecker.checkLatest(UpdateChecker.DEFAULT_GITHUB_REPO, current, appChannel)
                }
                if (update == null) {
                    availableUpdate = null
                    val channelLabel = if (language == AppLanguage.Ru) appChannel.ruTitle else appChannel.enTitle
                    "${text.noUpdateFound}: $current ($channelLabel)"
                } else {
                    val shouldNotify = availableUpdate?.version != update.version
                    availableUpdate = update
                    if (!manual && shouldNotify) context.notifyAvailableUpdate(update, text)
                    val tagLabel = if (update.isPrerelease) (if (language == AppLanguage.Ru) "Бета" else "Beta") else (if (language == AppLanguage.Ru) "Релиз" else "Release")
                    if (language == AppLanguage.Ru) "Доступно обновление ${update.version} ($tagLabel)" else "Update ${update.version} available ($tagLabel)"
                }
            }.getOrElse { "${text.updateFailed}: ${it.message ?: it.javaClass.simpleName}" }
            updateMessage = result
            updateBusy = false
        }
    }

    fun loadReleaseArchive() {
        if (isArchiveLoading) return
        isArchiveLoading = true
        scope.launch {
            archiveReleases = withContext(Dispatchers.IO) {
                UpdateChecker.fetchAllReleases()
            }
            isArchiveLoading = false
        }
    }

    fun rollbackToRelease(release: ReleaseArchiveItem) {
        if (isRollbackDownloading) return
        isRollbackDownloading = true
        Toast.makeText(context, if (language == AppLanguage.Ru) "Скачивание версии ${release.version}..." else "Downloading version ${release.version}...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val success = runCatching {
                val apk = withContext(Dispatchers.IO) {
                    UpdateChecker.downloadReleaseApk(context, release)
                }
                UpdateChecker.installApk(context, apk)
                true
            }.getOrElse {
                Toast.makeText(context, "${text.updateFailed}: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                false
            }
            isRollbackDownloading = false
            if (success) {
                rollbackConfirmRelease = null
                showArchiveDialog = false
            }
        }
    }

    fun installAvailableUpdate() {
        val update = availableUpdate ?: return
        if (updateBusy) return
        updateBusy = true
        updateMessage = "${text.downloading} ${update.version}..."
        scope.launch {
            updateMessage = runCatching {
                val apk = withContext(Dispatchers.IO) { UpdateChecker.downloadApk(context, update) }
                UpdateChecker.installApk(context, apk)
                "${text.installerOpened} ${update.version}"
            }.getOrElse { "${text.updateFailed}: ${it.message ?: it.javaClass.simpleName}" }
            updateBusy = false
        }
    }

    LaunchedEffect(Unit) {
        secret = withContext(Dispatchers.IO) { context.getOrCreateProxySecret() }
        delay(1200)
        showSplash = false
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            val running = ProxyServiceStatus.isRunning
            proxyStatus = if (running) {
                ProxyStatus(true, ProxyServiceStatus.getUptime(), ProxyServiceStatus.lastPing)
            } else {
                ProxyStatus(isStarting = ProxyServiceStatus.isStarting)
            }
            logLines = withContext(Dispatchers.IO) { ProxyLogger.snapshot().takeLast(120) }
            batteryUnrestricted = context.isIgnoringBatteryOptimizations()
            trafficSummary = TrafficStatsManager.getSummary(context)
            delay(1000)
        }
    }

    LaunchedEffect(autoUpdateEnabled, autoUpdateValue, autoUpdateUnit, language) {
        if (!autoUpdateEnabled) return@LaunchedEffect
        while (isActive) {
            runUpdateCheck(manual = false)
            delay((autoUpdateValue.toLong() * autoUpdateUnit.minutes * 60_000L).coerceAtMost(365L * 24 * 60 * 60_000L))
        }
    }

    if (showBetaWarningDialog) {
        AlertDialog(
            onDismissRequest = { showBetaWarningDialog = false },
            title = { Text(if (language == AppLanguage.Ru) "🧪 Включение Бета-канала" else "🧪 Enable Beta Channel") },
            text = {
                Text(
                    if (language == AppLanguage.Ru) {
                        "⚠️ Внимание: бета-сборки и снапшоты предназначены исключительно для предварительного тестирования.\n\nУстановка осуществляется на свой страх и риск — возможна нестабильная работа, ошибки прокси и повышенный расход батареи!\n\nВы действительно хотите переключиться на Бета-канал?"
                    } else {
                        "⚠️ Warning: Beta builds and snapshots are intended for preliminary testing only.\n\nInstallation is at your own risk — builds may contain bugs, crashes, or higher battery drain!\n\nAre you sure you want to switch to the Beta channel?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appChannel = AppChannel.Beta
                        context.saveProxyPref("app_channel", AppChannel.Beta.key)
                        showBetaWarningDialog = false
                        runUpdateCheck(manual = true)
                    }
                ) {
                    Text(
                        if (language == AppLanguage.Ru) "Включить бету" else "Enable Beta",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBetaWarningDialog = false }) {
                    Text(if (language == AppLanguage.Ru) "Отмена" else "Cancel")
                }
            },
        )
    }

    if (showArchiveDialog) {
        VersionArchiveDialog(
            language = language,
            currentVersion = UpdateChecker.currentVersion(context),
            releases = archiveReleases,
            isLoading = isArchiveLoading,
            onRefresh = { loadReleaseArchive() },
            onRollback = { rollbackConfirmRelease = it },
            onDismiss = { showArchiveDialog = false },
        )
    }

    if (rollbackConfirmRelease != null) {
        val targetRelease = rollbackConfirmRelease!!
        AlertDialog(
            onDismissRequest = { if (!isRollbackDownloading) rollbackConfirmRelease = null },
            title = { Text(if (language == AppLanguage.Ru) "⏪ Откат на версию ${targetRelease.version}" else "⏪ Rollback to ${targetRelease.version}") },
            text = {
                Text(
                    if (language == AppLanguage.Ru) {
                        "Вы действительно хотите скачать и установить версию ${targetRelease.version}?\n\nВаши сохранённые настройки и секретный ключ будут сохранены."
                    } else {
                        "Are you sure you want to download and install version ${targetRelease.version}?\n\nYour saved settings and secret key will be preserved."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { rollbackToRelease(targetRelease) },
                    enabled = !isRollbackDownloading,
                ) {
                    Text(if (isRollbackDownloading) (if (language == AppLanguage.Ru) "Скачивание..." else "Downloading...") else (if (language == AppLanguage.Ru) "Откатить и установить" else "Rollback & Install"))
                }
            },
            dismissButton = {
                if (!isRollbackDownloading) {
                    TextButton(onClick = { rollbackConfirmRelease = null }) {
                        Text(if (language == AppLanguage.Ru) "Отмена" else "Cancel")
                    }
                }
            },
        )
    }

    if (showTelegramClientDialog) {
        AlertDialog(
            onDismissRequest = { showTelegramClientDialog = false },
            title = { Text(if (language == AppLanguage.Ru) "Выберите Telegram" else "Choose Telegram app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    telegramClients.forEach { client ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                context.saveProxyPref(TELEGRAM_CLIENT_PREF, client.packageName)
                                context.openTelegram(link, client.packageName)
                                showTelegramClientDialog = false
                            },
                        ) { Text(client.label) }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showDisableAutoUpdateWarning) {
        AlertDialog(
            onDismissRequest = { showDisableAutoUpdateWarning = false },
            title = { Text(text.autoUpdates) },
            text = { Text(text.autoUpdatesWarning) },
            confirmButton = {
                TextButton(
                    onClick = {
                        autoUpdateEnabled = false
                        context.saveProxyPref(AUTO_UPDATE_ENABLED_PREF, false)
                        showDisableAutoUpdateWarning = false
                    },
                ) { Text(text.disableAnyway) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAutoUpdateWarning = false }) {
                    Text(text.keepEnabled)
                }
            },
        )
    }

    if (showQrDialog) {
        val localIp = NetworkUtils.getLocalIpAddress(context)
        val lanLink = if (localIp != null) {
            val cleanDomain = fakeTlsDomain.trim()
            val proxySecret = if (cleanDomain.isBlank()) "dd$secret" else "ee$secret${cleanDomain.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }}"
            "tg://proxy?server=$localIp&port=${ProxyConfig.PORT}&secret=$proxySecret"
        } else link
        QrDialog(link = lanLink, onDismiss = { showQrDialog = false })
    }

    if (showExportDialog) {
        val profile = ProxyProfile(
            id = "custom",
            ruName = "Пользовательский",
            enName = "Custom",
            ruDesc = "",
            enDesc = "",
            fakeTlsDomain = fakeTlsDomain,
            cfWorkerDomain = cfWorkerDomain,
            cfEnabled = cfEnabled,
            poolSize = poolSize,
            smartStandby = smartStandby,
            dcMappings = dcMappings,
        )
        ExportConfigDialog(language = language, jsonText = profile.exportToJson(secret), onDismiss = { showExportDialog = false })
    }

    if (showImportDialog) {
        ImportConfigDialog(
            language = language,
            onDismiss = { showImportDialog = false },
            onImport = { cfg ->
                if (cfg.secret != null) secret = cfg.secret
                fakeTlsDomain = cfg.fakeTlsDomain
                cfWorkerDomain = cfg.cfWorkerDomain
                cfEnabled = cfg.cfEnabled
                poolSize = cfg.poolSize
                smartStandby = cfg.smartStandby
                if (cfg.dcMappings.isNotBlank()) dcMappings = cfg.dcMappings
                context.saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
                context.saveProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, cfWorkerDomain)
                context.saveProxyPref(ProxyService.EXTRA_CF_ENABLED, cfEnabled)
                context.saveProxyPref(ProxyService.EXTRA_POOL_SIZE, poolSize.toString())
                context.saveProxyPref(ProxyService.EXTRA_SMART_STANDBY, smartStandby)
                if (cfg.dcMappings.isNotBlank()) context.saveProxyPref(ProxyService.EXTRA_DC_IPS, dcMappings)
                Toast.makeText(context, if (language == AppLanguage.Ru) "Настройки импортированы" else "Settings imported", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showSplash || secret.isBlank()) {
        SplashScreen(text)
        return
    }

    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("TG", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        Column {
                            Text("TgwsProxy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("127.0.0.1:1443", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (proxyStatus.isRunning) SignalMint
                                    else if (proxyStatus.isStarting) SignalAmber
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Text(
                            if (proxyStatus.isRunning) (if (language == AppLanguage.Ru) "Активен" else "Active")
                            else if (proxyStatus.isStarting) (if (language == AppLanguage.Ru) "Запуск..." else "Starting...")
                            else (if (language == AppLanguage.Ru) "Остановлен" else "Stopped"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (proxyStatus.isRunning) SignalMint else if (proxyStatus.isStarting) SignalAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        val isSelected = pagerState.currentPage == index
                        val tabName = when (tab) {
                            AppTab.Home -> if (language == AppLanguage.Ru) "Главная" else "Home"
                            AppTab.Settings -> if (language == AppLanguage.Ru) "Настройки" else "Settings"
                            AppTab.Logs -> if (language == AppLanguage.Ru) "Журнал" else "Logs"
                            AppTab.Help -> if (language == AppLanguage.Ru) "Справка" else "Help"
                        }
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = tabName,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            beyondViewportPageCount = 1,
            key = { AppTab.entries[it].name },
        ) { page ->
            Box(modifier = Modifier.fillMaxSize().background(appBackgroundBrush(themeMode))) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (AppTab.entries[page]) {
                    AppTab.Home -> HomePage(
                        text = text,
                        status = proxyStatus,
                        link = link,
                        logs = logLines,
                        cfEnabled = cfEnabled,
                        language = language,
                        trafficSummary = trafficSummary,
                        allowLan = allowLan,
                        secret = secret,
                        fakeTlsDomain = fakeTlsDomain,
                        onResetTraffic = {
                            TrafficStatsManager.resetStats(context)
                            trafficSummary = TrafficStatsManager.getSummary(context)
                        },
                        onShowQr = { showQrDialog = true },
                        onStart = {
                            if (!ProxyConfig.isValidDcMappings(dcMappings)) {
                                Toast.makeText(context, if (language == AppLanguage.Ru) "Исправьте список DC → IP" else "Fix the DC → IP list", Toast.LENGTH_SHORT).show()
                            } else {
                                context.startProxyService(secret, fakeTlsDomain, cfWorkerDomain, cfEnabled, poolSize, dcMappings, allowLan, smartStandby)
                            }
                            proxyStatus = ProxyStatus(isStarting = true)
                        },
                        onStop = {
                            context.stopService(Intent(context, ProxyService::class.java))
                            proxyStatus = ProxyStatus(false)
                        },
                        onCopyLink = {
                            context.copyToClipboard(link)
                            Toast.makeText(context, text.linkCopied, Toast.LENGTH_SHORT).show()
                        },
                        onOpenTelegram = {
                            val savedPackage = context.getProxyPref(TELEGRAM_CLIENT_PREF, "")
                            if (!context.openTelegram(link, savedPackage)) {
                                telegramClients = context.findTelegramClients(link)
                                when (telegramClients.size) {
                                    0 -> if (!context.openTelegramWithSystemChooser(link)) {
                                        Toast.makeText(context, if (language == AppLanguage.Ru) "Telegram не найден" else "Telegram app not found", Toast.LENGTH_SHORT).show()
                                    }
                                    1 -> {
                                        context.saveProxyPref(TELEGRAM_CLIENT_PREF, telegramClients.first().packageName)
                                        context.openTelegram(link, telegramClients.first().packageName)
                                    }
                                    else -> showTelegramClientDialog = true
                                }
                            }
                        },
                    )
                    AppTab.Logs -> LogsPage(
                        text = text,
                        logs = logLines,
                        onDownload = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { context.saveLogsToDownloads() }
                                Toast.makeText(context, if (result) text.logsSaved else text.logsSaveFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    AppTab.Settings -> SettingsPage(
                        text = text,
                        language = language,
                        onLanguageChange = {
                            language = it
                            context.saveProxyPref(LANGUAGE_PREF, it.code)
                        },
                        fakeTlsDomain = fakeTlsDomain,
                        onFakeTlsDomainChange = {
                            fakeTlsDomain = it.trim()
                            context.saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
                        },
                        cfWorkerDomain = cfWorkerDomain,
                        onCfWorkerDomainChange = {
                            cfWorkerDomain = ProxyConfig.cleanDomain(it)
                            context.saveProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, cfWorkerDomain)
                        },
                        dcMappings = dcMappings,
                        onDcMappingsChange = {
                            dcMappings = it
                            context.saveProxyPref(ProxyService.EXTRA_DC_IPS, it)
                        },
                        enabled = !proxyStatus.isRunning && !proxyStatus.isStarting,
                        cfEnabled = cfEnabled,
                        onCfEnabledChange = {
                            cfEnabled = it
                            context.saveProxyPref(ProxyService.EXTRA_CF_ENABLED, it)
                        },
                        allowLan = allowLan,
                        onAllowLanChange = {
                            allowLan = it
                            context.saveProxyPref(ProxyService.EXTRA_ALLOW_LAN, it)
                        },
                        smartStandby = smartStandby,
                        onSmartStandbyChange = {
                            smartStandby = it
                            context.saveProxyPref(ProxyService.EXTRA_SMART_STANDBY, it)
                        },
                        appChannel = appChannel,
                        onChannelChange = { newChannel ->
                            if (newChannel == AppChannel.Beta && appChannel != AppChannel.Beta) {
                                showBetaWarningDialog = true
                            } else {
                                appChannel = newChannel
                                context.saveProxyPref("app_channel", newChannel.key)
                                runUpdateCheck(manual = true)
                            }
                        },
                        onOpenVersionArchive = {
                            showArchiveDialog = true
                            loadReleaseArchive()
                        },
                        onApplyProfile = { profile ->
                            fakeTlsDomain = profile.fakeTlsDomain
                            cfWorkerDomain = profile.cfWorkerDomain
                            cfEnabled = profile.cfEnabled
                            poolSize = profile.poolSize
                            smartStandby = profile.smartStandby
                            if (profile.dcMappings.isNotBlank()) dcMappings = profile.dcMappings
                            context.saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
                            context.saveProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, cfWorkerDomain)
                            context.saveProxyPref(ProxyService.EXTRA_CF_ENABLED, cfEnabled)
                            context.saveProxyPref(ProxyService.EXTRA_POOL_SIZE, poolSize.toString())
                            context.saveProxyPref(ProxyService.EXTRA_SMART_STANDBY, smartStandby)
                            if (profile.dcMappings.isNotBlank()) context.saveProxyPref(ProxyService.EXTRA_DC_IPS, dcMappings)
                            if (proxyStatus.isRunning) {
                                context.startProxyService(secret, fakeTlsDomain, cfWorkerDomain, cfEnabled, poolSize, dcMappings, allowLan, smartStandby)
                                Toast.makeText(context, if (language == AppLanguage.Ru) "Профиль ${profile.name(true)} применен (прокси перезапущен)" else "Applied profile ${profile.name(false)} (proxy reloaded)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (language == AppLanguage.Ru) "Применен профиль: ${profile.name(true)}" else "Applied profile: ${profile.name(false)}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenExport = { showExportDialog = true },
                        onOpenImport = { showImportDialog = true },
                        secret = secret,
                        onCopySecret = {
                            context.copyToClipboard(secret)
                            Toast.makeText(context, text.linkCopied, Toast.LENGTH_SHORT).show()
                        },
                        poolSize = poolSize,
                        onPoolSizeChange = {
                            poolSize = it
                            context.saveProxyPref(ProxyService.EXTRA_POOL_SIZE, it.toString())
                        },
                        updateMessage = updateMessage,
                        updateBusy = updateBusy,
                        availableUpdate = availableUpdate,
                        autoUpdateEnabled = autoUpdateEnabled,
                        autoUpdateValue = autoUpdateValue,
                        autoUpdateUnit = autoUpdateUnit,
                        autoStartProxy = autoStartProxy,
                        themeMode = themeMode,
                        batteryUnrestricted = batteryUnrestricted,
                        onBatterySettings = { context.requestBatteryOptimizationExemption() },
                        onAutoUpdateEnabledChange = { enabled ->
                            if (!enabled) {
                                showDisableAutoUpdateWarning = true
                            } else {
                                autoUpdateEnabled = true
                                context.saveProxyPref(AUTO_UPDATE_ENABLED_PREF, true)
                            }
                        },
                        onAutoUpdateValueChange = {
                            autoUpdateValue = it.coerceIn(1, 999)
                            context.saveProxyPref(AUTO_UPDATE_VALUE_PREF, autoUpdateValue.toString())
                        },
                        onAutoUpdateUnitChange = {
                            autoUpdateUnit = it
                            context.saveProxyPref(AUTO_UPDATE_UNIT_PREF, it.name)
                        },
                        onAutoStartProxyChange = {
                            autoStartProxy = it
                            context.saveProxyPref(AUTO_START_PROXY_PREF, it)
                        },
                        onThemeModeChange = onThemeModeChange,
                        onForgetTelegramClient = {
                            context.saveProxyPref(TELEGRAM_CLIENT_PREF, "")
                            Toast.makeText(context, if (language == AppLanguage.Ru) "Выбор Telegram сброшен" else "Telegram choice reset", Toast.LENGTH_SHORT).show()
                        },
                        onCheckUpdate = { runUpdateCheck(manual = true) },
                        onInstallUpdate = { installAvailableUpdate() },
                    )
                    AppTab.Help -> HelpPage(language)
                }
                }
            }
        }
    }
}

@Composable
private fun appBackgroundBrush(mode: AppThemeMode): Brush = when (mode) {
    AppThemeMode.Light -> Brush.verticalGradient(listOf(Color(0xFFF4F5F8), Color(0xFFECEFF4)))
    AppThemeMode.Dark -> Brush.verticalGradient(listOf(Color(0xFF111215), Color(0xFF16181F)))
    AppThemeMode.Aurora -> Brush.verticalGradient(listOf(Color(0xFF0F1517), Color(0xFF12191F)))
    AppThemeMode.Sunset -> Brush.verticalGradient(listOf(Color(0xFF161214), Color(0xFF18151D)))
}

@Composable
private fun BackgroundOrbs(mode: AppThemeMode) {
    // 0% background overhead - clean minimal theme
}

@Composable
private fun HomePage(
    text: UiStrings,
    status: ProxyStatus,
    link: String,
    logs: List<String>,
    cfEnabled: Boolean,
    language: AppLanguage,
    trafficSummary: TrafficSummary,
    allowLan: Boolean,
    secret: String,
    fakeTlsDomain: String,
    onResetTraffic: () -> Unit,
    onShowQr: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenTelegram: () -> Unit,
) {
    val stats = remember(logs) { latestStats(logs) }
    PageTitle(if (text.start == "Запустить") "Главная" else "Home", "127.0.0.1:1443 · MTProto WS Proxy")
    ProxyHeroCard(
        text = text,
        status = status,
        link = link,
        stats = stats,
        cfEnabled = cfEnabled,
        onStart = onStart,
        onStop = onStop,
        onOpenTelegram = onOpenTelegram,
        onCopyLink = onCopyLink,
    )
    TrafficStatsCard(language, trafficSummary, onResetTraffic)
    LanSharingCard(
        language = language,
        allowLan = allowLan,
        isRunning = status.isRunning,
        secret = secret,
        fakeTlsDomain = fakeTlsDomain,
        onShowQr = onShowQr,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(if (text.start == "Запустить") "Пул WebSocket" else "WS Pool", "${stats.ws} active", Modifier.weight(1f))
        StatTile(if (text.start == "Запустить") "Cloudflare" else "Cloudflare", if (cfEnabled) "Priority" else "Off", Modifier.weight(1f), color = if (cfEnabled) SignalMint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(if (text.start == "Запустить") "Сессии" else "Active", stats.active, Modifier.weight(1f))
        StatTile(if (text.start == "Запустить") "Ошибки" else "Errors", stats.errors, Modifier.weight(1f), color = getErrorColor(stats.errors))
    }
    LogsCard(text, logs.takeLast(6))
}

@Composable
private fun LogsPage(
    text: UiStrings,
    logs: List<String>,
    onDownload: () -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(LogCategory.General) }
    val visibleLogs = remember(logs, category) {
        if (category == LogCategory.Errors) logs.filter { " E " in it || " W " in it || "ERROR" in it || "WARN" in it } else logs
    }
    PageTitle(text.debugLog, if (visibleLogs.isEmpty()) text.noEventsYet else "${visibleLogs.size} lines")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogCategory.entries.forEach { item ->
            val label = when (item) {
                LogCategory.General -> if (text.start == "Запустить") "Общие" else "General"
                LogCategory.Errors -> if (text.start == "Запустить") "Ошибки" else "Errors"
            }
            if (category == item) Button(modifier = Modifier.weight(1f), onClick = { category = item }) { ButtonText(label) }
            else OutlinedButton(modifier = Modifier.weight(1f), onClick = { category = item }) { ButtonText(label) }
        }
    }
    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onDownload) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        ButtonText(text.downloadLogs)
    }
    LogsCard(text, visibleLogs)
}

@Composable
private fun SettingsPage(
    text: UiStrings,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    appChannel: AppChannel,
    onChannelChange: (AppChannel) -> Unit,
    onOpenVersionArchive: () -> Unit,
    fakeTlsDomain: String,
    onFakeTlsDomainChange: (String) -> Unit,
    cfWorkerDomain: String,
    onCfWorkerDomainChange: (String) -> Unit,
    dcMappings: String,
    onDcMappingsChange: (String) -> Unit,
    enabled: Boolean,
    cfEnabled: Boolean,
    onCfEnabledChange: (Boolean) -> Unit,
    allowLan: Boolean,
    onAllowLanChange: (Boolean) -> Unit,
    smartStandby: Boolean,
    onSmartStandbyChange: (Boolean) -> Unit,
    onApplyProfile: (ProxyProfile) -> Unit,
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
    secret: String,
    onCopySecret: () -> Unit,
    poolSize: Int,
    onPoolSizeChange: (Int) -> Unit,
    updateMessage: String,
    updateBusy: Boolean,
    availableUpdate: UpdateInfo?,
    autoUpdateEnabled: Boolean,
    autoUpdateValue: Int,
    autoUpdateUnit: UpdateIntervalUnit,
    autoStartProxy: Boolean,
    themeMode: AppThemeMode,
    batteryUnrestricted: Boolean,
    onBatterySettings: () -> Unit,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    onAutoUpdateValueChange: (Int) -> Unit,
    onAutoUpdateUnitChange: (UpdateIntervalUnit) -> Unit,
    onAutoStartProxyChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onForgetTelegramClient: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val context = LocalContext.current
    PageTitle(text.proxyOptions, text.currentVersion + ": " + UpdateChecker.currentVersion(context) + " (Build #" + UpdateChecker.currentVersionCode(context) + ")")
    LanguageCard(text, language, onLanguageChange)
    ThemePickerCard(language, themeMode, onThemeModeChange)
    BuildMetadataCard(language, appChannel)
    UpdateChannelCard(language, appChannel, onChannelChange, onOpenVersionArchive)
    PresetsAndBackupCard(
        language = language,
        enabled = enabled,
        onApplyProfile = onApplyProfile,
        onOpenExport = onOpenExport,
        onOpenImport = onOpenImport,
    )
    SettingsCard(
        text = text,
        fakeTlsDomain = fakeTlsDomain,
        onFakeTlsDomainChange = onFakeTlsDomainChange,
        cfWorkerDomain = cfWorkerDomain,
        onCfWorkerDomainChange = onCfWorkerDomainChange,
        dcMappings = dcMappings,
        onDcMappingsChange = onDcMappingsChange,
        enabled = enabled,
        cfEnabled = cfEnabled,
        onCfEnabledChange = onCfEnabledChange,
        secret = secret,
        onCopySecret = onCopySecret,
    )
    DomainBenchmarkCard(
        language = language,
        currentDomain = fakeTlsDomain,
        onSelectDomain = { newDomain ->
            onFakeTlsDomainChange(newDomain)
            if (enabled) {
                context.startProxyService(secret, newDomain, cfWorkerDomain, cfEnabled, poolSize, dcMappings, allowLan, smartStandby)
                Toast.makeText(context, if (language == AppLanguage.Ru) "Применен домен: $newDomain (прокси перезапущен)" else "Applied domain: $newDomain (proxy reloaded)", Toast.LENGTH_SHORT).show()
            }
        },
    )
    LanSettingsCard(
        language = language,
        allowLan = allowLan,
        onAllowLanChange = onAllowLanChange,
    )
    SmartStandbyCard(
        language = language,
        smartStandby = smartStandby,
        onSmartStandbyChange = onSmartStandbyChange,
    )
    PoolSizeCard(text, poolSize, enabled, onPoolSizeChange)
    AutoUpdateCard(
        text = text,
        enabled = autoUpdateEnabled,
        value = autoUpdateValue,
        unit = autoUpdateUnit,
        onEnabledChange = onAutoUpdateEnabledChange,
        onValueChange = onAutoUpdateValueChange,
        onUnitChange = onAutoUpdateUnitChange,
    )
    AutoStartCard(language, autoStartProxy, onAutoStartProxyChange)
    TelegramClientCard(language, onForgetTelegramClient)
    BatteryOptimizationCard(text, batteryUnrestricted, onBatterySettings)
    UpdateCard(
        text = text,
        message = updateMessage,
        busy = updateBusy,
        update = availableUpdate,
        onCheck = onCheckUpdate,
        onInstall = onInstallUpdate,
    )
}

@Composable
private fun DomainBenchmarkCard(
    language: AppLanguage,
    currentDomain: String,
    onSelectDomain: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DomainPingResult>>(emptyList()) }
    val isRu = language == AppLanguage.Ru

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (isRu) "Тест задержки доменов" else "Domain Latency Test",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (isRu) "Поиск лучшего маршрута FakeTLS / Cloudflare" else "Find best FakeTLS / Cloudflare route",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        if (!isTesting) {
                            isTesting = true
                            coroutineScope.launch {
                                results = DomainBenchmark.runBenchmark(currentDomain, isRu)
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting,
                ) {
                    ButtonText(if (isTesting) (if (isRu) "Тест..." else "Testing...") else (if (isRu) "Проверить" else "Check ping"))
                }
            }

            if (results.isNotEmpty()) {
                val fastest = results.firstOrNull { it.isSuccess }
                if (fastest != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isRu) "Лучший: ${fastest.domain} (${fastest.pingMs} ms)" else "Fastest: ${fastest.domain} (${fastest.pingMs} ms)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(
                            onClick = {
                                onSelectDomain(fastest.domain)
                                Toast.makeText(context, if (isRu) "Применен домен: ${fastest.domain}" else "Applied domain: ${fastest.domain}", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            ButtonText(if (isRu) "Применить" else "Apply")
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.forEach { result ->
                        val isCurrent = result.domain.equals(currentDomain, ignoreCase = true)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        result.domain,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (result.description.isNotBlank()) {
                                        Text(
                                            result.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (result.isSuccess) "${result.pingMs} ms" else (if (isRu) "Таймаут" else "Timeout"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            !result.isSuccess -> MaterialTheme.colorScheme.error
                                            result.pingMs < 100 -> SignalMint
                                            result.pingMs < 250 -> SignalAmber
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                    )
                                    if (!isCurrent) {
                                        TextButton(
                                            onClick = {
                                                onSelectDomain(result.domain)
                                                Toast.makeText(context, if (isRu) "Выбран: ${result.domain}" else "Selected: ${result.domain}", Toast.LENGTH_SHORT).show()
                                            },
                                        ) {
                                            Text(if (isRu) "Выбрать" else "Select")
                                        }
                                    } else {
                                        Text(
                                            if (isRu) "Активен ✓" else "Active ✓",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficStatsCard(
    language: AppLanguage,
    summary: TrafficSummary,
    onReset: () -> Unit,
) {
    val isRu = language == AppLanguage.Ru
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isRu) "Статистика трафика" else "Traffic Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onReset) {
                    Text(if (isRu) "Сбросить" else "Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (isRu) "Сегодня" else "Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("↓ ${summary.formattedTodayDown()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = SignalMint)
                        Text("↑ ${summary.formattedTodayUp()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (isRu) "За всё время" else "All Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("↓ ${summary.formattedTotalDown()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = SignalBlue)
                        Text("↑ ${summary.formattedTotalUp()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanSharingCard(
    language: AppLanguage,
    allowLan: Boolean,
    isRunning: Boolean,
    secret: String,
    fakeTlsDomain: String,
    onShowQr: () -> Unit,
) {
    val context = LocalContext.current
    val isRu = language == AppLanguage.Ru
    val localIp = remember(allowLan, isRunning) { NetworkUtils.getLocalIpAddress(context) }
    val lanLink = remember(localIp, secret, fakeTlsDomain) {
        if (localIp != null) {
            val cleanDomain = fakeTlsDomain.trim()
            val proxySecret = if (cleanDomain.isBlank()) "dd$secret" else "ee$secret${cleanDomain.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }}"
            "tg://proxy?server=$localIp&port=${ProxyConfig.PORT}&secret=$proxySecret"
        } else ""
    }

    if (!allowLan) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isRu) "Раздача в сети Wi-Fi" else "Local Wi-Fi Sharing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (localIp != null) "IP: $localIp:${ProxyConfig.PORT}" else (if (isRu) "Wi-Fi не подключен" else "Wi-Fi not connected"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (localIp != null) SignalMint else MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (localIp != null && isRunning) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onShowQr,
                    ) {
                        ButtonText(if (isRu) "Показать QR" else "Show QR")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.copyToClipboard(lanLink)
                            Toast.makeText(context, if (isRu) "Ссылка для ПК скопирована" else "PC link copied", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        ButtonText(if (isRu) "Копировать для ПК" else "Copy PC link")
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetsAndBackupCard(
    language: AppLanguage,
    enabled: Boolean,
    onApplyProfile: (ProxyProfile) -> Unit,
    onOpenExport: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val isRu = language == AppLanguage.Ru
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (isRu) "Готовые профили (Пресеты)" else "Quick Profiles & Presets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyProfile.PRESETS.forEach { profile ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onApplyProfile(profile) },
                        enabled = enabled,
                    ) {
                        Text(
                            when (profile.id) {
                                "fast_cf" -> if (isRu) "🚀 Скорость" else "🚀 Speed"
                                "stealth_faketls" -> if (isRu) "🛡️ Скрытный" else "🛡️ Stealth"
                                else -> if (isRu) "🔋 Эко" else "🔋 Eco"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenExport,
                ) {
                    ButtonText(if (isRu) "Экспорт конфига" else "Export Config")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenImport,
                    enabled = enabled,
                ) {
                    ButtonText(if (isRu) "Импорт конфига" else "Import Config")
                }
            }
        }
    }
}

@Composable
private fun SmartStandbyCard(
    language: AppLanguage,
    smartStandby: Boolean,
    onSmartStandbyChange: (Boolean) -> Unit,
) {
    val isRu = language == AppLanguage.Ru
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (isRu) "Умный режим сна (Smart Standby)" else "Smart Standby Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isRu) "Снижает активность при выключенном экране без трафика" else "Reduces background polling when screen is OFF and idle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = smartStandby, onCheckedChange = onSmartStandbyChange)
        }
    }
}

@Composable
private fun LanSettingsCard(
    language: AppLanguage,
    allowLan: Boolean,
    onAllowLanChange: (Boolean) -> Unit,
) {
    val isRu = language == AppLanguage.Ru
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (isRu) "Раздача по Wi-Fi (LAN Mode)" else "LAN Sharing (0.0.0.0)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (isRu) "Разрешить подключение ПК и других устройств в локальной сети" else "Allow PC and other local network devices to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = allowLan, onCheckedChange = onAllowLanChange)
        }
    }
}

@Composable
private fun QrDialog(
    link: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val qrMatrix = remember(link) { runCatching { QrGenerator.encode(link) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QR-код для подключения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Отсканируйте камерой Telegram на компьютере или планшете",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (qrMatrix != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        modifier = Modifier.size(220.dp).padding(8.dp),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val moduleSize = size.width / qrMatrix.size
                            for (r in 0 until qrMatrix.size) {
                                for (c in 0 until qrMatrix.size) {
                                    if (qrMatrix.isDark(r, c)) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = Offset(c * moduleSize, r * moduleSize),
                                            size = androidx.compose.ui.geometry.Size(moduleSize, moduleSize),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                context.copyToClipboard(link)
                Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text("Скопировать ссылку")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun ExportConfigDialog(
    language: AppLanguage,
    jsonText: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isRu = language == AppLanguage.Ru
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRu) "Экспорт конфигурации" else "Export Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isRu) "Скопируйте настройки для переноса на другое устройство:" else "Copy your settings to transfer to another device:")
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                context.copyToClipboard(jsonText)
                Toast.makeText(context, if (isRu) "Конфиг скопирован" else "Config copied", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text(if (isRu) "Скопировать" else "Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isRu) "Закрыть" else "Close") }
        },
    )
}

@Composable
private fun ImportConfigDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onImport: (ProxyProfile.Companion.ImportedConfig) -> Unit,
) {
    val isRu = language == AppLanguage.Ru
    var rawText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRu) "Импорт конфигурации" else "Import Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (isRu) "Вставьте JSON конфиг или ссылку tg://proxy:" else "Paste JSON config or tg://proxy link:")
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it; errorText = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("{ ... }") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = ProxyProfile.parseImport(rawText)
                if (parsed != null) {
                    onImport(parsed)
                    onDismiss()
                } else {
                    errorText = if (isRu) "Неверный формат конфигурации" else "Invalid configuration format"
                }
            }) {
                Text(if (isRu) "Применить" else "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isRu) "Отмена" else "Cancel") }
        },
    )
}

private fun Modifier.liquidGlass(radius: Dp = 16.dp): Modifier = this

@Composable
private fun BatteryOptimizationCard(text: UiStrings, unrestricted: Boolean, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.batteryProtection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (unrestricted) text.batteryUnrestricted else text.batteryRestricted,
                style = MaterialTheme.typography.bodySmall,
                color = if (unrestricted) SignalMint else MaterialTheme.colorScheme.error,
            )
            if (!unrestricted) {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings) {
                    ButtonText(text.allowBackgroundWork)
                }
            }
        }
    }
}

@Composable
private fun PoolSizeCard(text: UiStrings, poolSize: Int, enabled: Boolean, onPoolSizeChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.wsPool, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text.wsPoolHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(2, 4, 6).forEach { size ->
                    if (size == poolSize) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onPoolSizeChange(size) },
                        ) { Text(size.toString()) }
                    } else {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onPoolSizeChange(size) },
                        ) { Text(size.toString()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen(text: UiStrings) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0A84FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_telegram_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(52.dp),
                )
            }
            Text(
                "TG WS Proxy",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text.splashSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpPage(language: AppLanguage) {
    val ru = language == AppLanguage.Ru
    PageTitle(if (ru) "Помощь" else "Help", "Rust + Tokio core")
    HelpCategory(
        title = if (ru) "Подключение" else "Connection",
        items = if (ru) listOf(
            "Запуск" to "Нажми Start и дождись статуса Active. Локальный адрес остаётся 127.0.0.1:1443.",
            "Telegram" to "Нажми открыть в Telegram и включи добавленный MTProto-прокси. Повторно удалять его обычно не нужно.",
            "Если висит подключение" to "Открой логи: если down растёт, данные идут. Если только err или timeout, проверь сеть и попробуй Stop/Start.",
        ) else listOf(
            "Start" to "Tap Start and wait for Active. The local endpoint stays 127.0.0.1:1443.",
            "Telegram" to "Open the Telegram link and enable the added MTProto proxy. You normally do not need to delete it again.",
            "Stuck connecting" to "Check logs: if down grows, data is flowing. If only err or timeout grows, check the network and try Stop/Start.",
        ),
    )
    HelpCategory(
        title = if (ru) "Сеть" else "Network",
        items = if (ru) listOf(
            "Cloudflare" to "Пустое поле Cloudflare использует встроенный список доменов. Workers.dev relay больше не нужен.",
            "Пул 2 / 4 / 6" to "4 — сбалансированный режим. 6 уменьшает задержку запуска Telegram, но держит больше готовых WSS-соединений и расходует больше батареи.",
            "Самый низкий пинг" to "Если прямой WSS работает у оператора, отключи Cloudflare CDN. При блокировке включи его обратно.",
            "TCP fallback" to "Если Cloudflare не отвечает, ядро пробует прямой TCP fallback и не ломает клиентское соединение сразу.",
            "Keepalive" to "Rust/Tokio ядро держит TCP и WebSocket соединения живыми ping/pong и watchdog-проверками.",
        ) else listOf(
            "Cloudflare" to "An empty Cloudflare field uses the built-in domain list. The workers.dev relay is no longer needed.",
            "Pool 2 / 4 / 6" to "4 is balanced. 6 reduces Telegram startup latency but keeps more WSS connections ready and uses more battery.",
            "Lowest latency" to "If direct WSS works on your carrier, disable Cloudflare CDN. Turn it back on when direct routing is blocked.",
            "TCP fallback" to "If Cloudflare does not respond, the core tries direct TCP fallback without immediately breaking the client connection.",
            "Keepalive" to "The Rust/Tokio core keeps TCP and WebSocket connections alive with ping/pong and watchdog checks.",
        ),
    )
    HelpCategory(
        title = if (ru) "Логи и обновления" else "Logs and updates",
        items = if (ru) listOf(
            "Статистика" to "cf - Cloudflare маршруты, tcp_fb - прямой TCP fallback, err - ошибки, down - данные к Telegram.",
            "Скачать логи" to "На вкладке Logs можно сохранить файл логов в загрузки телефона и отправить его для диагностики.",
            "Обновления" to "Автопроверка ищет релиз на GitHub. Отключать её можно, но лучше оставлять включённой.",
        ) else listOf(
            "Stats" to "cf means Cloudflare routes, tcp_fb is direct TCP fallback, err is errors, down is data sent to Telegram.",
            "Download logs" to "The Logs tab can save a log file to the phone Downloads folder for debugging.",
            "Updates" to "Auto-check looks for GitHub releases. You can disable it, but keeping it enabled is recommended.",
        ),
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun BuildMetadataCard(language: AppLanguage, channel: AppChannel) {
    val context = LocalContext.current
    val isRu = language == AppLanguage.Ru
    val versionName = UpdateChecker.currentVersion(context)
    val versionCode = UpdateChecker.currentVersionCode(context)
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Universal"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isRu) "Информация о сборке" else "Build Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (channel == AppChannel.Beta) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        if (channel == AppChannel.Beta) (if (isRu) "🧪 БЕТА / СНАПШОТ" else "🧪 BETA SNAPSHOT") else (if (isRu) "🟢 СТАБИЛЬНАЯ" else "🟢 STABLE"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (channel == AppChannel.Beta) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (isRu) "Версия" else "Version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("v$versionName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (isRu) "Номер сборки" else "Build Code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("#$versionCode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (isRu) "Архитектура" else "Arch (ABI)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(abi.substringBefore("-"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateChannelCard(
    language: AppLanguage,
    channel: AppChannel,
    onChannelChange: (AppChannel) -> Unit,
    onOpenArchive: () -> Unit,
) {
    val isRu = language == AppLanguage.Ru

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (isRu) "Канал обновлений и архив" else "Update Channel & Archive",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChannel.entries.forEach { item ->
                    val selected = channel == item
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onChannelChange(item) },
                        border = BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) (if (item == AppChannel.Beta) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) (if (item == AppChannel.Beta) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) else Color.Transparent,
                        ),
                    ) {
                        Text(
                            if (item == AppChannel.Beta) (if (isRu) "🧪 Бета / Снапшот" else "🧪 Beta Snapshot") else (if (isRu) "🟢 Стабильная" else "🟢 Stable"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (channel == AppChannel.Beta) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("⚠️", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (isRu) {
                                "Внимание: вы используете тестовый Бета-канал. Сборки могут содержать экспериментальный код и работать нестабильно. Установка на свой страх и риск!"
                            } else {
                                "Warning: You are using the experimental Beta channel. Builds may be unstable. Install at your own risk!"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenArchive,
            ) {
                ButtonText(if (isRu) "📜 Архив всех версий и откат (Rollback)" else "📜 Version Archive & Rollback")
            }
        }
    }
}

@Composable
private fun VersionArchiveDialog(
    language: AppLanguage,
    currentVersion: String,
    releases: List<ReleaseArchiveItem>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onRollback: (ReleaseArchiveItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val isRu = language == AppLanguage.Ru

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isRu) "Архив версий и откат" else "Version History & Rollback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isRu) "Текущая версия: v$currentVersion" else "Current version: v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Text(if (isLoading) "⏳" else "🔄", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                if (isLoading && releases.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (isRu) "Загрузка версий с GitHub..." else "Loading releases from GitHub...", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        releases.forEach { release ->
                            val isCurrent = release.version.equals(currentVersion, ignoreCase = true)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                                ),
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "v${release.version}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (isCurrent) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                ) {
                                                    Text(
                                                        if (isRu) "УСТАНОВЛЕНО" else "INSTALLED",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                            if (release.isPrerelease) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.errorContainer,
                                                ) {
                                                    Text(
                                                        "BETA",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (release.publishedAt.isNotBlank()) {
                                        Text(
                                            if (isRu) "Выпущено: ${release.publishedAt}" else "Released: ${release.publishedAt}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    if (release.releaseNotes.isNotBlank()) {
                                        Text(
                                            release.releaseNotes.take(300),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 4,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        Button(
                                            onClick = { onRollback(release) },
                                            enabled = !isCurrent,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                            ),
                                        ) {
                                            Text(
                                                if (isCurrent) (if (isRu) "Текущая" else "Current") else (if (isRu) "Откатить на v${release.version}" else "Rollback to v${release.version}"),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isRu) "Закрыть" else "Close")
            }
        },
    )
}

@Composable
private fun LanguageCard(text: UiStrings, language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), enabled = language != AppLanguage.Ru, onClick = { onLanguageChange(AppLanguage.Ru) }) {
                ButtonText(text.russian)
            }
            OutlinedButton(modifier = Modifier.weight(1f), enabled = language != AppLanguage.En, onClick = { onLanguageChange(AppLanguage.En) }) {
                ButtonText(text.english)
            }
        }
    }
}

@Composable
private fun ThemePickerCard(language: AppLanguage, selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (language == AppLanguage.Ru) "Оформление" else "Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            AppThemeMode.entries.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        val label = when (mode) {
                            AppThemeMode.Light -> if (language == AppLanguage.Ru) "Светлая" else "Light"
                            AppThemeMode.Dark -> if (language == AppLanguage.Ru) "Тёмная" else "Dark"
                            AppThemeMode.Aurora -> if (language == AppLanguage.Ru) "Аврора" else "Aurora"
                            AppThemeMode.Sunset -> if (language == AppLanguage.Ru) "Закат" else "Sunset"
                        }
                        if (selected == mode) Button(modifier = Modifier.weight(1f), onClick = { onSelect(mode) }) { ButtonText(label) }
                        else OutlinedButton(modifier = Modifier.weight(1f), onClick = { onSelect(mode) }) { ButtonText(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoStartCard(language: AppLanguage, enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (language == AppLanguage.Ru) "Автозапуск прокси" else "Proxy auto-start", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (language == AppLanguage.Ru) "Запускать после перезагрузки телефона" else "Start after the phone reboots",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun TelegramClientCard(language: AppLanguage, onForget: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (language == AppLanguage.Ru) "Приложение Telegram" else "Telegram application", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (language == AppLanguage.Ru) "Выбор клиента запоминается. Нажмите ниже, чтобы выбрать заново." else "Your client choice is remembered. Reset it to choose again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onForget) {
                ButtonText(if (language == AppLanguage.Ru) "Выбрать заново" else "Choose again")
            }
        }
    }
}

@Composable
private fun AutoUpdateCard(
    text: UiStrings,
    enabled: Boolean,
    value: Int,
    unit: UpdateIntervalUnit,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Int) -> Unit,
    onUnitChange: (UpdateIntervalUnit) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text.autoUpdates, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) text.autoUpdatesEnabled else text.autoUpdatesWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(text.updateInterval, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value.toString(),
                enabled = enabled,
                singleLine = true,
                label = { Text(if (text.start == "Запустить") "Проверять раз в" else "Check every") },
                onValueChange = { input ->
                    input.filter(Char::isDigit).take(3).toIntOrNull()?.let(onValueChange)
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateIntervalUnit.entries.forEach { option ->
                    val label = when (option) {
                        UpdateIntervalUnit.Minutes -> if (text.start == "Запустить") "Минуты" else "Minutes"
                        UpdateIntervalUnit.Hours -> if (text.start == "Запустить") "Часы" else "Hours"
                        UpdateIntervalUnit.Days -> if (text.start == "Запустить") "Дни" else "Days"
                    }
                    val selected = unit == option
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = { onUnitChange(option) },
                    ) {
                        ButtonText(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpCategory(title: String, items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            items.forEach { (label, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ButtonText(label: String, color: Color = Color.Unspecified) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, compact: Boolean = false) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ControlPanel(text: UiStrings, running: Boolean, locked: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(
                    if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = if (running) onStop else onStart, enabled = !locked || running) {
                    Icon(
                        Icons.Rounded.PowerSettingsNew,
                        contentDescription = if (running) text.stop else text.start,
                        tint = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (running) text.active else text.stopped, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${ProxyConfig.HOST}:${ProxyConfig.PORT}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!running) Button(onClick = onStart, enabled = !locked) { ButtonText(text.start) }
            if (running) OutlinedButton(onClick = onStop) { ButtonText(text.stop) }
        }
    }
}

private fun latestStats(logs: List<String>): LatestStats {
    val line = logs.lastOrNull { it.contains("Rust stats:") } ?: return LatestStats()
    fun value(key: String): String = line.substringAfter("$key=", "").substringBefore(" ").ifBlank { "0" }
    return LatestStats(
        active = value("active"),
        cf = value("cf"),
        ws = value("ws"),
        tcp = value("tcp_fb"),
        errors = value("err"),
        up = value("up"),
        down = value("down"),
    )
}

@Composable
private fun getErrorColor(errors: String): Color {
    return if ((errors.toIntOrNull() ?: 0) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
}

@Composable
private fun UpdateCard(
    text: UiStrings,
    message: String,
    busy: Boolean,
    update: UpdateInfo?,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (update != null) {
                    if (text.start == "Запустить") "Доступно обновление ${update.version}" else "Update ${update.version} available"
                } else text.updateCheck,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (update != null && update.releaseNotes.isNotBlank()) {
                Text(
                    if (text.start == "Запустить") "Что изменилось" else "What's new",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(update.releaseNotes.take(1800), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = if (update == null) onCheck else onInstall, enabled = !busy) {
                ButtonText(if (busy) text.working else if (update != null) text.installRequiredUpdate else text.checkForUpdate)
            }
            if (update != null) {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onCheck, enabled = !busy) {
                    ButtonText(text.checkForUpdate)
                }
                Text(
                    if (text.start == "Запустить") "Обновление необязательное: прокси продолжит работать." else "The update is optional: the proxy remains available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LogsCard(text: UiStrings, lines: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text.debugLog, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (lines.isEmpty()) {
                Text(text.noEventsYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            " E " in line || "ERROR" in line -> MaterialTheme.colorScheme.error
                            " W " in line || "WARN" in line -> SignalAmber
                            "WS" in line -> MaterialTheme.colorScheme.primary
                            else -> SignalMint
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyHeroCard(
    text: UiStrings,
    status: ProxyStatus,
    link: String,
    stats: LatestStats,
    cfEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTelegram: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (text.start == "Запустить") "Статус MTProto" else "MTProto Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "AES-CTR + FakeTLS",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    status.isRunning -> SignalMint
                                    status.isStarting -> SignalAmber
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                    Text(
                        when {
                            status.isRunning -> if (text.start == "Запустить") "Подключено" else "Connected"
                            status.isStarting -> if (text.start == "Запустить") "Запуск..." else "Starting..."
                            else -> if (text.start == "Запустить") "Отключено" else "Stopped"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (status.isRunning) {
                    Text(
                        status.uptime,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        if (text.start == "Запустить") "ВХОДЯЩИЙ" else "INCOMING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stats.down,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = SignalMint,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (text.start == "Запустить") "ИСХОДЯЩИЙ" else "OUTGOING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stats.up,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isRunning) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenTelegram,
                    ) {
                        ButtonText(text.openInTelegram)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onCopyLink,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        ButtonText(text.copyTelegramLink)
                    }
                    Button(
                        modifier = Modifier.weight(0.8f),
                        onClick = if (status.isRunning) onStop else onStart,
                        enabled = !status.isStarting,
                        colors = if (status.isRunning) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        },
                    ) {
                        ButtonText(if (status.isRunning) text.stop else text.start)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(5.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Header(status: ProxyStatus, text: UiStrings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TgwsProxy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (status.isRunning) text.active else text.stopped,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.isRunning) SignalMint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier.size(12.dp).clip(CircleShape).background(
                        if (status.isRunning) SignalMint else MaterialTheme.colorScheme.outline,
                    ),
                )
            }
            if (status.isRunning) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(text.uptime, status.uptime)
                    Metric(text.service, if (status.localPing >= 0) text.online else "N/A", getPingColor(status.localPing))
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, color = color)
    }
}

@Composable
private fun ConnectionCard(text: UiStrings, secret: String, link: String, status: ProxyStatus) {
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.localEndpoint, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${ProxyConfig.HOST}:${ProxyConfig.PORT}", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text.secret, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (secretVisible) secret.chunked(8).joinToString(" ") else "•••••••• •••••••• •••••••• ••••••••",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = { secretVisible = !secretVisible }) {
                    Icon(
                        if (secretVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = text.secret,
                    )
                }
            }
            if (status.isRunning) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text.currentLink, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(link.take(96), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SettingsCard(
    text: UiStrings,
    fakeTlsDomain: String,
    onFakeTlsDomainChange: (String) -> Unit,
    cfWorkerDomain: String,
    onCfWorkerDomainChange: (String) -> Unit,
    dcMappings: String,
    onDcMappingsChange: (String) -> Unit,
    enabled: Boolean,
    cfEnabled: Boolean,
    onCfEnabledChange: (Boolean) -> Unit,
    secret: String,
    onCopySecret: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.proxyOptions, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = secret,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(text.secret) },
                trailingIcon = {
                    IconButton(onClick = onCopySecret) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = text.copyTelegramLink)
                    }
                },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = fakeTlsDomain,
                onValueChange = onFakeTlsDomainChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(text.fakeTlsDomain) },
                placeholder = { Text(text.emptyDdSecret) },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = cfWorkerDomain,
                onValueChange = onCfWorkerDomainChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(text.cfWorkerDomain) },
                placeholder = { Text(text.emptyDirectFallback) },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = dcMappings,
                onValueChange = onDcMappingsChange,
                enabled = enabled,
                minLines = 3,
                maxLines = 6,
                label = { Text(if (text.start == "Запустить") "Датацентры Telegram (DC → IP)" else "Telegram datacenters (DC → IP)") },
                placeholder = { Text("2:149.154.167.51\n4:149.154.167.91", fontFamily = FontFamily.Monospace) },
                isError = !ProxyConfig.isValidDcMappings(dcMappings),
                supportingText = {
                    Text(
                        when {
                            !ProxyConfig.isValidDcMappings(dcMappings) -> if (text.start == "Запустить") "Неверный формат. Одна строка: номерDC:IPv4" else "Invalid format. One line: dcNumber:IPv4"
                            text.start == "Запустить" -> "Необязательно. Каждая строка направляет Telegram DC на выбранный IP. Пусто = безопасные адреса по умолчанию."
                            else -> "Optional. Each line routes a Telegram DC to the selected IP. Empty uses safe defaults."
                        },
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text.cloudflareCdn, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Switch(checked = cfEnabled, enabled = enabled, onCheckedChange = onCfEnabledChange)
            }
        }
    }
}

@Composable
private fun ControlButtons(text: UiStrings, running: Boolean, locked: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(modifier = Modifier.weight(1f), onClick = onStart, enabled = !running && !locked) { ButtonText(text.start) }
        OutlinedButton(modifier = Modifier.weight(1f), onClick = onStop, enabled = running) { ButtonText(text.stop) }
    }
}

@Composable
private fun getPingColor(ping: Long): Color = when {
    ping < 0 -> MaterialTheme.colorScheme.error
    ping < 50 -> MaterialTheme.colorScheme.primary
    ping < 150 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun Context.startProxyService(
    secret: String,
    fakeTlsDomain: String,
    cfWorkerDomain: String,
    cfEnabled: Boolean,
    poolSize: Int,
    dcMappings: String,
    allowLan: Boolean = false,
    smartStandby: Boolean = true,
) {
    val cleanFakeTlsDomain = ProxyConfig.normalizeDomain(fakeTlsDomain)
    val cleanWorkerDomain = ProxyConfig.normalizeDomain(cfWorkerDomain)
    saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, cleanFakeTlsDomain)
    saveProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, cleanWorkerDomain)
    saveProxyPref(ProxyService.EXTRA_CF_ENABLED, cfEnabled)
    saveProxyPref(ProxyService.EXTRA_ALLOW_LAN, allowLan)
    saveProxyPref(ProxyService.EXTRA_SMART_STANDBY, smartStandby)
    saveProxyPref(ProxyService.EXTRA_CF_DOMAIN, cleanWorkerDomain)
    saveProxyPref(ProxyService.EXTRA_POOL_SIZE, poolSize.toString())
    saveProxyPref(ProxyService.EXTRA_DC_IPS, ProxyConfig.normalizeDcMappings(dcMappings))
    val intent = Intent(this, ProxyService::class.java)
        .putExtra(ProxyService.EXTRA_SECRET, secret)
        .putExtra(ProxyService.EXTRA_FAKE_TLS_DOMAIN, cleanFakeTlsDomain)
        .putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, cleanWorkerDomain)
        .putExtra(ProxyService.EXTRA_CF_ENABLED, cfEnabled)
        .putExtra(ProxyService.EXTRA_ALLOW_LAN, allowLan)
        .putExtra(ProxyService.EXTRA_SMART_STANDBY, smartStandby)
        .putExtra(ProxyService.EXTRA_POOL_SIZE, poolSize)
        .putExtra(ProxyService.EXTRA_DC_IPS, ProxyConfig.normalizeDcMappings(dcMappings))
        .putExtra(ProxyService.EXTRA_CF_DOMAIN, cleanWorkerDomain)
    ContextCompat.startForegroundService(this, intent)
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    return runCatching {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)
}

@SuppressLint("BatteryLife") // The user explicitly opts a persistent proxy out of Doze.
private fun Context.requestBatteryOptimizationExemption() {
    if (isIgnoringBatteryOptimizations()) return
    val packageUri = "package:$packageName".toUri()
    val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(directRequest) }.recoverCatching {
        startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { firstError ->
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { ProxyLogger.w("Unable to open battery settings", firstError) }
    }
}

private fun Context.getOrCreateProxySecret(): String {
    return SecureSecretStore.getOrCreate(this)
}

private fun Context.getProxyPref(key: String, default: String): String {
    return getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).getString(key, default).orEmpty()
}

private fun Context.getProxyPref(key: String, default: Boolean): Boolean {
    return getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).getBoolean(key, default)
}

private fun Context.saveProxyPref(key: String, value: String) {
    getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit { putString(key, value) }
}

private fun Context.saveProxyPref(key: String, value: Boolean) {
    getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit { putBoolean(key, value) }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram proxy link", text))
}

private fun Context.findTelegramClients(link: String): List<TelegramClient> {
    val intent = Intent(Intent.ACTION_VIEW, link.toUri())
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return activities
        .map { TelegramClient(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun Context.openTelegram(link: String, packageName: String): Boolean {
    if (packageName.isBlank()) return false
    return runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(packageName))
        true
    }.getOrDefault(false)
}

private fun Context.openTelegramWithSystemChooser(link: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, link.toUri())
    startActivity(Intent.createChooser(intent, null))
    true
}.getOrDefault(false)

private fun Context.saveLogsToDownloads(): Boolean {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val fileName = "tgwsproxy-logs-$timestamp.txt"
    val content = ProxyLogger.exportText().ifBlank { "No log lines\n" }
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            java.io.File(downloads, fileName).writeText(content, Charsets.UTF_8)
        }
        true
    }.getOrDefault(false)
}

private fun Context.notifyAvailableUpdate(update: UpdateInfo, text: UiStrings) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
    }

    val notificationManager = getSystemService(NotificationManager::class.java)
    val channel = NotificationChannel(
        UPDATE_CHANNEL_ID,
        "Updates",
        NotificationManager.IMPORTANCE_HIGH,
    )
    notificationManager.createNotificationChannel(channel)

    val pendingIntent = PendingIntent.getActivity(
        this,
        20,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (text.start == "Запустить") "Доступно обновление ${update.version}" else "Update ${update.version} available")
        .setContentText(if (text.start == "Запустить") "Можно установить в удобное время" else "Install whenever it is convenient")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
}

private const val UPDATE_CHANNEL_ID = "updates"
private const val UPDATE_NOTIFICATION_ID = 2001
private const val LANGUAGE_PREF = "ui_language"
private const val THEME_PREF = "theme_mode"
internal const val AUTO_START_PROXY_PREF = "auto_start_proxy"
private const val TELEGRAM_CLIENT_PREF = "telegram_client_package"
private const val AUTO_UPDATE_ENABLED_PREF = "auto_update_enabled"
private const val AUTO_UPDATE_VALUE_PREF = "auto_update_interval_value"
private const val AUTO_UPDATE_UNIT_PREF = "auto_update_interval_unit"
internal const val PROXY_PREFS = "proxy"

@Preview(showBackground = true)
@Composable
fun ProxyScreenPreview() {
    TgwsProxyAndroidTheme { ProxyScreen() }
}
