package com.tgwsproxy.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.android.proxy.ProxyLogger
import com.tgwsproxy.android.ui.theme.TgwsProxyAndroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent { TgwsProxyAndroidTheme { ProxyScreen() } }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }
}

data class ProxyStatus(
    val isRunning: Boolean = false,
    val uptime: String = "00:00",
    val localPing: Long = -1,
)

@Composable
fun ProxyScreen() {
    val context = LocalContext.current
    var proxyStatus by remember { mutableStateOf(ProxyStatus()) }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    val secret = rememberSaveable { context.getOrCreateProxySecret() }
    var fakeTlsDomain by rememberSaveable { mutableStateOf(context.getProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, "")) }
    var updateMessage by remember { mutableStateOf("Current version: ${UpdateChecker.currentVersion(context)}") }
    var updateBusy by remember { mutableStateOf(false) }
    var requiredUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val link = remember(secret, fakeTlsDomain) { ProxyConfig.telegramProxyLink(secret, fakeTlsDomain) }

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
                val logs = ProxyLogger.snapshot().takeLast(12)
                withContext(Dispatchers.Main) {
                    proxyStatus = next
                    logLines = logs
                }
                delay(1000)
            }
        }
        onDispose { job.cancel() }
    }

    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            val result = runCatching {
                val current = UpdateChecker.currentVersion(context)
                UpdateChecker.checkLatest(UpdateChecker.DEFAULT_GITHUB_REPO, current)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { update ->
                    requiredUpdate = update
                    if (update != null) {
                        context.stopService(Intent(context, ProxyService::class.java))
                        proxyStatus = ProxyStatus(false)
                        updateMessage = "Required update ${update.version}. Install it to continue."
                    }
                }.onFailure {
                    updateMessage = "Update check failed. Current version: ${UpdateChecker.currentVersion(context)}"
                }
            }
        }
        onDispose { job.cancel() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header(proxyStatus)
                ConnectionCard(secret, link, proxyStatus)
                SettingsCard(
                    fakeTlsDomain = fakeTlsDomain,
                    onFakeTlsDomainChange = {
                        fakeTlsDomain = it.trim()
                        context.saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
                    },
                    enabled = !proxyStatus.isRunning,
                )
                UpdateCard(
                    message = updateMessage,
                    busy = updateBusy,
                    required = requiredUpdate != null,
                    onCheck = {
                        updateBusy = true
                        updateMessage = if (requiredUpdate != null) "Downloading required update..." else "Checking GitHub Releases..."
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = runCatching {
                                val current = UpdateChecker.currentVersion(context)
                                val update = requiredUpdate ?: UpdateChecker.checkLatest(UpdateChecker.DEFAULT_GITHUB_REPO, current)
                                if (update == null) {
                                    "No update found. Current version: $current"
                                } else {
                                    withContext(Dispatchers.Main) { updateMessage = "Downloading ${update.version}..." }
                                    val apk = UpdateChecker.downloadApk(context, update)
                                    withContext(Dispatchers.Main) { UpdateChecker.installApk(context, apk) }
                                    "Installer opened for version ${update.version}"
                                }
                            }.getOrElse { "Update failed: ${it.message ?: it.javaClass.simpleName}" }
                            withContext(Dispatchers.Main) {
                                updateMessage = result
                                updateBusy = false
                            }
                        }
                    },
              )
                ControlButtons(
                    running = proxyStatus.isRunning,
                    locked = requiredUpdate != null,
                    onStart = {
                        context.startProxyService(secret, fakeTlsDomain)
                        proxyStatus = ProxyStatus(true)
                    },
                    onStop = {
                        context.stopService(Intent(context, ProxyService::class.java))
                        proxyStatus = ProxyStatus(false)
                    },
                )
                LogsCard(logLines)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.copyToClipboard(link)
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Copy Telegram link") }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                ) { Text("Open in Telegram") }
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
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
                if (required) "Required update" else "Update check",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onCheck, enabled = !busy) {
                Text(
                    when {
                        busy -> "Working..."
                        required -> "Install required update"
                        else -> "Check for update"
                    }
                )
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LogsCard(lines: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Debug log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (lines.isEmpty()) {
                Text("No events yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun Header(status: ProxyStatus) {
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
                        if (status.isRunning) "Active" else "Stopped",
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
                    Metric("Uptime", status.uptime)
                    Metric("Service", if (status.localPing >= 0) "Online" else "N/A", getPingColor(status.localPing))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.proxy_app_icon),
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
private fun ConnectionCard(secret: String, link: String, status: ProxyStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Local endpoint", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${ProxyConfig.HOST}:${ProxyConfig.PORT}", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text("Secret", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(secret.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            if (status.isRunning) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Current link", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(link.take(96), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SettingsCard(
    fakeTlsDomain: String,
    onFakeTlsDomainChange: (String) -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Proxy options", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = fakeTlsDomain,
                onValueChange = onFakeTlsDomainChange,
                enabled = enabled,
                singleLine = true,
                label = { Text("Fake TLS domain") },
                placeholder = { Text("empty = dd secret") },
            )
        }
    }
}

@Composable
private fun ControlButtons(running: Boolean, locked: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(modifier = Modifier.weight(1f), onClick = onStart, enabled = !running && !locked) { Text("Start") }
        OutlinedButton(modifier = Modifier.weight(1f), onClick = onStop, enabled = running) { Text("Stop") }
    }
}

@Composable
private fun getPingColor(ping: Long): Color = when {
    ping < 0 -> MaterialTheme.colorScheme.error
    ping < 50 -> MaterialTheme.colorScheme.primary
    ping < 150 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun Context.startProxyService(secret: String, fakeTlsDomain: String) {
    saveProxyPref(ProxyService.EXTRA_SECRET, secret)
    saveProxyPref(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
    saveProxyPref(ProxyService.EXTRA_CF_ENABLED, false)
    saveProxyPref(ProxyService.EXTRA_CF_DOMAIN, "")
    val intent = Intent(this, ProxyService::class.java)
        .putExtra(ProxyService.EXTRA_SECRET, secret)
        .putExtra(ProxyService.EXTRA_FAKE_TLS_DOMAIN, fakeTlsDomain)
        .putExtra(ProxyService.EXTRA_CF_ENABLED, false)
        .putExtra(ProxyService.EXTRA_CF_DOMAIN, "")
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

private const val PROXY_PREFS = "proxy"

@Preview(showBackground = true)
@Composable
fun ProxyScreenPreview() {
    TgwsProxyAndroidTheme { ProxyScreen() }
}
