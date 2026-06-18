package com.tgwsproxy.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.android.proxy.ProxyLogger
import com.tgwsproxy.android.ui.theme.TgwsProxyAndroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        requestStoragePermission()
        setContent { TgwsProxyAndroidTheme(darkTheme = true) { ProxyScreen() } }
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
)

private enum class AppLanguage(val code: String) {
    Ru("ru"),
    En("en"),
}

private enum class AppTab {
    Home,
    Logs,
    Settings,
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
        updateCheck = "Проверка обновлений",
        requiredUpdate = "Обязательное обновление",
        working = "Работаю...",
        installRequiredUpdate = "Установить обновление",
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
        autoUpdatesWarning = "Отключать автообновления не стоит: через них приходят обязательные исправления прокси и безопасности. Но если нужно, отключить можно.",
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
        updateCheck = "Update check",
        requiredUpdate = "Required update",
        working = "Working...",
        installRequiredUpdate = "Install required update",
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
        autoUpdatesWarning = "Disabling auto updates is not recommended: required proxy and security fixes arrive through them. You can still disable it.",
        disableAnyway = "Disable",
        keepEnabled = "Keep enabled",
        updateInterval = "Check interval",
        minutesShort = "min",
        hoursShort = "h",
        splashSubtitle = "Rust core, Cloudflare fallback",
    )
}

@Composable
fun ProxyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var language by rememberSaveable {
        mutableStateOf(
            if (context.getProxyPref(LANGUAGE_PREF, AppLanguage.Ru.code) == AppLanguage.En.code) AppLanguage.En else AppLanguage.Ru,
        )
    }
    val text = strings(language)
    var proxyStatus by remember { mutableStateOf(ProxyStatus()) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    val secret = rememberSaveable { context.getOrCreateProxySecret() }
    var fakeTlsDomain by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "")) }
    var cfWorkerDomain by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, ProxyConfig.DEFAULT_CF_WORKER_DOMAIN)) }
    var updateMessage by remember(language) { mutableStateOf("${text.currentVersion}: ${UpdateChecker.currentVersion(context)}") }
    var updateBusy by remember { mutableStateOf(false) }
    var requiredUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var autoUpdateEnabled by rememberSaveable { mutableStateOf(context.getProxyPref(AUTO_UPDATE_ENABLED_PREF, true)) }
    var autoUpdateMinutes by rememberSaveable { mutableStateOf(context.getProxyPref(AUTO_UPDATE_INTERVAL_PREF, DEFAULT_AUTO_UPDATE_MINUTES.toString()).toIntOrNull()?.coerceIn(15, 24 * 60) ?: DEFAULT_AUTO_UPDATE_MINUTES) }
    var showDisableAutoUpdateWarning by rememberSaveable { mutableStateOf(false) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    val link = remember(secret) { ProxyConfig.telegramProxyLink(secret, "") }

    fun runUpdateCheck(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (manual) updateMessage = if (requiredUpdate != null) text.downloadingRequiredUpdate else text.checkingGithub
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val current = UpdateChecker.currentVersion(context)
                val update = requiredUpdate ?: UpdateChecker.checkLatest(UpdateChecker.DEFAULT_GITHUB_REPO, current)
                if (update == null) {
                    "${text.noUpdateFound}: $current"
                } else {
                    withContext(Dispatchers.Main) {
                        requiredUpdate = update
                        context.stopService(Intent(context, ProxyService::class.java))
                        proxyStatus = ProxyStatus(false)
                        context.notifyRequiredUpdate(update, text)
                    }
                    if (manual) {
                        withContext(Dispatchers.Main) { updateMessage = "${text.downloading} ${update.version}..." }
                        val apk = UpdateChecker.downloadApk(context, update)
                        withContext(Dispatchers.Main) { UpdateChecker.installApk(context, apk) }
                        "${text.installerOpened} ${update.version}"
                    } else {
                        "${text.requiredUpdate} ${update.version}. ${text.installToContinue}"
                    }
                }
            }.getOrElse { "${text.updateFailed}: ${it.message ?: it.javaClass.simpleName}" }
            withContext(Dispatchers.Main) {
                updateMessage = result
                updateBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(1200)
        showSplash = false
    }

    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            while (isActive) {
                val running = ProxyServiceStatus.isRunning
                val next = if (running) {
                    ProxyStatus(true, ProxyServiceStatus.getUptime(), ProxyServiceStatus.lastPing)
                } else {
                    ProxyStatus(false)
                }
                val logs = ProxyLogger.snapshot().takeLast(120)
                withContext(Dispatchers.Main) {
                    proxyStatus = next
                    logLines = logs
                }
                delay(1000)
            }
        }
        onDispose { job.cancel() }
    }

    DisposableEffect(autoUpdateEnabled, autoUpdateMinutes, language) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            if (!autoUpdateEnabled) return@launch
            while (isActive) {
                withContext(Dispatchers.Main) { runUpdateCheck(manual = false) }
                delay(autoUpdateMinutes * 60_000L)
            }
        }
        onDispose { job.cancel() }
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

    if (showSplash) {
        SplashScreen(text)
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomTabs(
                selectedTab = selectedTab,
                language = language,
                onSelect = { selectedTab = it },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (selectedTab) {
                    AppTab.Home -> HomePage(
                        text = text,
                        status = proxyStatus,
                        secret = secret,
                        link = link,
                        logs = logLines,
                        requiredUpdate = requiredUpdate != null,
                        onStart = {
                            context.startProxyService(secret, fakeTlsDomain, cfWorkerDomain)
                            proxyStatus = ProxyStatus(true)
                        },
                        onStop = {
                            context.stopService(Intent(context, ProxyService::class.java))
                            proxyStatus = ProxyStatus(false)
                        },
                        onCopyLink = {
                            context.copyToClipboard(link)
                            Toast.makeText(context, text.linkCopied, Toast.LENGTH_SHORT).show()
                        },
                        onOpenTelegram = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                    )
                    AppTab.Logs -> LogsPage(
                        text = text,
                        logs = logLines,
                        onDownload = {
                            val result = context.saveLogsToDownloads()
                            Toast.makeText(context, if (result) text.logsSaved else text.logsSaveFailed, Toast.LENGTH_SHORT).show()
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
                        enabled = !proxyStatus.isRunning,
                        updateMessage = updateMessage,
                        updateBusy = updateBusy,
                        requiredUpdate = requiredUpdate != null,
                        autoUpdateEnabled = autoUpdateEnabled,
                        autoUpdateMinutes = autoUpdateMinutes,
                        onAutoUpdateEnabledChange = { enabled ->
                            if (!enabled) {
                                showDisableAutoUpdateWarning = true
                            } else {
                                autoUpdateEnabled = true
                                context.saveProxyPref(AUTO_UPDATE_ENABLED_PREF, true)
                            }
                        },
                        onAutoUpdateMinutesChange = {
                            autoUpdateMinutes = it
                            context.saveProxyPref(AUTO_UPDATE_INTERVAL_PREF, it.toString())
                        },
                        onCheckUpdate = { runUpdateCheck(manual = true) },
                    )
                    AppTab.Help -> HelpPage(language)
                }
            }
        }
    }
}
@Composable
private fun BottomTabs(
    selectedTab: AppTab,
    language: AppLanguage,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            AppTab.Home -> Icons.Rounded.Home
                            AppTab.Logs -> Icons.Rounded.Article
                            AppTab.Settings -> Icons.Rounded.Settings
                            AppTab.Help -> Icons.Rounded.HelpOutline
                        },
                        contentDescription = tabTitle(tab, language),
                    )
                },
                label = { Text(tabTitle(tab, language)) },
            )
        }
    }
}

private fun tabTitle(tab: AppTab, language: AppLanguage): String {
    return when (language) {
        AppLanguage.Ru -> when (tab) {
            AppTab.Home -> "Главная"
            AppTab.Logs -> "Логи"
            AppTab.Settings -> "Настройки"
            AppTab.Help -> "Помощь"
        }
        AppLanguage.En -> when (tab) {
            AppTab.Home -> "Home"
            AppTab.Logs -> "Logs"
            AppTab.Settings -> "Settings"
            AppTab.Help -> "Help"
        }
    }
}

@Composable
private fun HomePage(
    text: UiStrings,
    status: ProxyStatus,
    secret: String,
    link: String,
    logs: List<String>,
    requiredUpdate: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenTelegram: () -> Unit,
) {
    val stats = remember(logs) { latestStats(logs) }
    Header(status, text)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("CF", stats.cf, Modifier.weight(1f))
        StatTile("Active", stats.active, Modifier.weight(1f))
        StatTile("Errors", stats.errors, Modifier.weight(1f), color = getErrorColor(stats.errors))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Up", stats.up, Modifier.weight(1f), compact = true)
        StatTile("Down", stats.down, Modifier.weight(1f), compact = true)
    }
    ControlPanel(text, status.isRunning, requiredUpdate, onStart, onStop)
    ConnectionCard(text, secret, link, status)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(modifier = Modifier.weight(1f), onClick = onCopyLink) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(text.copyTelegramLink)
        }
        Button(modifier = Modifier.weight(1f), onClick = onOpenTelegram) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(text.openInTelegram)
        }
    }
    LogsCard(text, logs.takeLast(6))
}

@Composable
private fun LogsPage(
    text: UiStrings,
    logs: List<String>,
    onDownload: () -> Unit,
) {
    PageTitle(text.debugLog, if (logs.isEmpty()) text.noEventsYet else "${logs.size} lines")
    FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onDownload) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text.downloadLogs)
    }
    LogsCard(text, logs)
}

@Composable
private fun SettingsPage(
    text: UiStrings,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    fakeTlsDomain: String,
    onFakeTlsDomainChange: (String) -> Unit,
    cfWorkerDomain: String,
    onCfWorkerDomainChange: (String) -> Unit,
    enabled: Boolean,
    updateMessage: String,
    updateBusy: Boolean,
    requiredUpdate: Boolean,
    autoUpdateEnabled: Boolean,
    autoUpdateMinutes: Int,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    onAutoUpdateMinutesChange: (Int) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    PageTitle(text.proxyOptions, text.currentVersion + ": " + UpdateChecker.currentVersion(LocalContext.current))
    LanguageCard(text, language, onLanguageChange)
    SettingsCard(
        text = text,
        fakeTlsDomain = fakeTlsDomain,
        onFakeTlsDomainChange = onFakeTlsDomainChange,
        cfWorkerDomain = cfWorkerDomain,
        onCfWorkerDomainChange = onCfWorkerDomainChange,
        enabled = enabled,
    )
    AutoUpdateCard(
        text = text,
        enabled = autoUpdateEnabled,
        minutes = autoUpdateMinutes,
        onEnabledChange = onAutoUpdateEnabledChange,
        onMinutesChange = onAutoUpdateMinutesChange,
    )
    UpdateCard(
        text = text,
        message = updateMessage,
        busy = updateBusy,
        required = requiredUpdate,
        onCheck = onCheckUpdate,
    )
}

@Composable
private fun SplashScreen(text: UiStrings) {
    val infinite = rememberInfiniteTransition(label = "splash")
    val scale by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "mark-scale",
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "mark-alpha",
    )
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(108.dp),
                )
            }
            Text("TG WS Proxy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(text.splashSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelpPage(language: AppLanguage) {
    val ru = language == AppLanguage.Ru
    PageTitle(if (ru) "Помощь" else "Help", "Rust + Tokio core")
    HelpCard(
        title = if (ru) "Как подключаться" else "How to connect",
        body = if (ru) {
            "Запусти прокси, нажми открыть в Telegram и включи добавленный локальный MTProto-прокси. Адрес остаётся 127.0.0.1:1443."
        } else {
            "Start the proxy, open the Telegram link, then enable the local MTProto proxy. The endpoint stays 127.0.0.1:1443."
        },
    )
    HelpCard(
        title = if (ru) "Cloudflare режим" else "Cloudflare mode",
        body = if (ru) {
            "Пустое поле Cloudflare использует встроенный список доменов и TCP fallback. Старый workers.dev больше не нужен."
        } else {
            "An empty Cloudflare field uses the built-in domain list and TCP fallback. The old workers.dev relay is no longer needed."
        },
    )
    HelpCard(
        title = if (ru) "Что значат логи" else "Reading logs",
        body = if (ru) {
            "cf показывает подключения через Cloudflare, tcp_fb - прямой TCP fallback, err - ошибки WS. Если down растёт, Telegram получает данные."
        } else {
            "cf means Cloudflare routes, tcp_fb is direct TCP fallback, err is WS errors. If down grows, Telegram is receiving data."
        },
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
private fun LanguageCard(text: UiStrings, language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), enabled = language != AppLanguage.Ru, onClick = { onLanguageChange(AppLanguage.Ru) }) {
                Text(text.russian)
            }
            OutlinedButton(modifier = Modifier.weight(1f), enabled = language != AppLanguage.En, onClick = { onLanguageChange(AppLanguage.En) }) {
                Text(text.english)
            }
        }
    }
}

@Composable
private fun AutoUpdateCard(
    text: UiStrings,
    enabled: Boolean,
    minutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onMinutesChange: (Int) -> Unit,
) {
    val options = listOf(30, 60, 180, 360)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val label = if (option < 60) "${option}${text.minutesShort}" else "${option / 60}${text.hoursShort}"
                    val selected = minutes == option
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        onClick = { onMinutesChange(option) },
                    ) {
                        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, compact: Boolean = false) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ControlPanel(text: UiStrings, running: Boolean, locked: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = if (running) onStop else onStart, enabled = !locked || running) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (running) text.stop else text.start)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (running) text.active else text.stopped, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${ProxyConfig.HOST}:${ProxyConfig.PORT}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!running) Button(onClick = onStart, enabled = !locked) { Text(text.start) }
            if (running) OutlinedButton(onClick = onStop) { Text(text.stop) }
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
    required: Boolean,
    onCheck: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (required) text.requiredUpdate else text.updateCheck,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onCheck, enabled = !busy) {
                Text(
                    when {
                        busy -> text.working
                        required -> text.installRequiredUpdate
                        else -> text.checkForUpdate
                    }
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text.debugLog, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (lines.isEmpty()) {
                Text(text.noEventsYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun Header(status: ProxyStatus, text: UiStrings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TG WS Proxy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (status.isRunning) text.active else text.stopped,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (status.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier.size(14.dp).clip(CircleShape).background(
                        if (status.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    ),
                )
            }
            if (status.isRunning) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(text.uptime, status.uptime)
                    Metric(text.service, if (status.localPing >= 0) text.online else "N/A", getPingColor(status.localPing))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(112.dp),
                    contentScale = ContentScale.Fit,
                )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.localEndpoint, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${ProxyConfig.HOST}:${ProxyConfig.PORT}", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(text.secret, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(secret.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
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
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text.proxyOptions, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        }
    }
}

@Composable
private fun ControlButtons(text: UiStrings, running: Boolean, locked: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(modifier = Modifier.weight(1f), onClick = onStart, enabled = !running && !locked) { Text(text.start) }
        OutlinedButton(modifier = Modifier.weight(1f), onClick = onStop, enabled = running) { Text(text.stop) }
    }
}

@Composable
private fun getPingColor(ping: Long): Color = when {
    ping < 0 -> MaterialTheme.colorScheme.error
    ping < 50 -> MaterialTheme.colorScheme.primary
    ping < 150 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun Context.startProxyService(secret: String, fakeTlsDomain: String, cfWorkerDomain: String) {
    val cleanWorkerDomain = ProxyConfig.cleanDomain(cfWorkerDomain).takeIf { it.endsWith(".co.uk") }.orEmpty()
    saveProxyPref(ProxyService.EXTRA_SECRET, secret)
    saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "")
    saveProxyPref(ProxyService.EXTRA_CF_WORKER_DOMAIN, cleanWorkerDomain)
    saveProxyPref(ProxyService.EXTRA_CF_ENABLED, true)
    saveProxyPref(ProxyService.EXTRA_CF_DOMAIN, cleanWorkerDomain)
    val intent = Intent(this, ProxyService::class.java)
        .putExtra(ProxyService.EXTRA_SECRET, secret)
        .putExtra(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "")
        .putExtra(ProxyService.EXTRA_CF_WORKER_DOMAIN, cleanWorkerDomain)
        .putExtra(ProxyService.EXTRA_CF_ENABLED, true)
        .putExtra(ProxyService.EXTRA_CF_DOMAIN, cleanWorkerDomain)
    ContextCompat.startForegroundService(this, intent)
}

private fun Context.getOrCreateProxySecret(): String {
    val stored = getProxyPref(ProxyService.EXTRA_SECRET, "")
    if (stored.isNotBlank()) return stored
    val generated = ProxyConfig.generateSecret()
    saveProxyPref(ProxyService.EXTRA_SECRET, generated)
    return generated
}

private fun Context.getProxyPref(key: String, default: String): String {
    return getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).getString(key, default).orEmpty()
}

private fun Context.getProxyPref(key: String, default: Boolean): Boolean {
    return getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).getBoolean(key, default)
}

private fun Context.saveProxyPref(key: String, value: String) {
    getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
}

private fun Context.saveProxyPref(key: String, value: Boolean) {
    getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram proxy link", text))
}

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

private fun Context.notifyRequiredUpdate(update: UpdateInfo, text: UiStrings) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
    }

    val notificationManager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_HIGH,
        )
        notificationManager.createNotificationChannel(channel)
    }

    val pendingIntent = PendingIntent.getActivity(
        this,
        20,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("${text.requiredUpdate} ${update.version}")
        .setContentText(text.installToContinue)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
}

private const val UPDATE_CHANNEL_ID = "updates"
private const val UPDATE_NOTIFICATION_ID = 2001
private const val LANGUAGE_PREF = "ui_language"
private const val AUTO_UPDATE_ENABLED_PREF = "auto_update_enabled"
private const val AUTO_UPDATE_INTERVAL_PREF = "auto_update_interval_minutes"
private const val DEFAULT_AUTO_UPDATE_MINUTES = 30
private const val PROXY_PREFS = "proxy"

@Preview(showBackground = true)
@Composable
fun ProxyScreenPreview() {
    TgwsProxyAndroidTheme { ProxyScreen() }
}
