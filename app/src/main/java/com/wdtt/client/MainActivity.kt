package com.wdtt.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wdtt.client.ui.AppUpdateDialog
import com.wdtt.client.ui.dashboard.DashboardScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification permission handled
    }

    @Volatile
    private var pendingAfterVpnGranted: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val cont = pendingAfterVpnGranted
        pendingAfterVpnGranted = null
        if (cont == null) return@registerForActivityResult
        if (android.net.VpnService.prepare(this) == null) {
            cont()
        } else {
            TunnelManager.cancelConnectingIfNeeded()
            Toast.makeText(this, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun prepareVpnThen(onGranted: () -> Unit) {
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingAfterVpnGranted = onGranted
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            onGranted()
        }
    }

    fun toggleConnection() {
        if (TunnelManager.running.value || TunnelManager.isConnecting.value) {
            TunnelControl.stop(applicationContext)
        } else {
            lifecycleScope.launch {
                val store = SettingsStore(applicationContext)
                val peer = store.peer.first()
                val wgConf = store.wireguardConfig.first()
                val activeProto = store.activeProtocolType.first()
                if (activeProto == ConnectionProfile.PROTOCOL_YANDEX_DOCS) {
                    Toast.makeText(
                        this@MainActivity,
                        "Режим Яндекс.Документы (OpenFlux) в разработке. Выберите Обычный VPN или Обход БС.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                if (peer.isBlank() && wgConf.isBlank()) {
                    Toast.makeText(this@MainActivity, "Сначала добавьте подписку или выберите сервер", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val existingReport = com.wdtt.client.detector.WhiteListDetectorManager.report.value
                val devOverride = store.developerModeEnabled.first() && store.devAllowBypassOnFreeNet.first()
                if (!devOverride && activeProto != ConnectionProfile.PROTOCOL_WIREGUARD &&
                    (existingReport == null || existingReport.viaVpn)
                ) {
                    Toast.makeText(this@MainActivity, "Проверка сети на Белый Список…", Toast.LENGTH_SHORT).show()
                }
                val gate = com.wdtt.client.detector.WhiteListDetectorManager.canStartSavedTunnel(applicationContext)
                if (!gate.allowed) {
                    val msg = gate.message ?: "Интернет свободен — Обход БС отключён."
                    TunnelManager.lastFatalError.value = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    return@launch
                }
                prepareVpnThen {
                    TunnelControl.startFromSavedSettings(applicationContext)
                }
            }
        }
    }

    fun startQrScanner() {
        try {
            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawText = barcode.rawValue ?: ""
                    if (rawText.isNotBlank()) {
                        lifecycleScope.launch {
                            val profilesStore = ProfilesStore(applicationContext)
                            val res = profilesStore.addSubscription(rawText)
                            if (res.isSuccess) {
                                Toast.makeText(this@MainActivity, "Подписка добавлена!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Ошибка: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Ошибка сканирования: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Сканер недоступен: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}

        var currentActivity: MainActivity? = null
        val pendingFileUri = mutableStateOf<Uri?>(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            AppShortcuts.ACTION_START_TUNNEL -> {
                if (!TunnelManager.running.value && !TunnelManager.isConnecting.value) {
                    lifecycleScope.launch {
                        val gate = com.wdtt.client.detector.WhiteListDetectorManager.canStartSavedTunnel(applicationContext)
                        if (!gate.allowed) {
                            val msg = gate.message ?: "Интернет свободен — Обход БС отключён."
                            TunnelManager.lastFatalError.value = msg
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        prepareVpnThen { TunnelControl.startFromSavedSettings(applicationContext) }
                    }
                }
            }
            AppShortcuts.ACTION_STOP_TUNNEL -> TunnelControl.stop(applicationContext)
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    pendingFileUri.value = uri
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        currentActivity = this
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
        VkAuthWebViewManager.checkAndShowPendingAuth(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
        if (currentActivity == this) {
            currentActivity = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestNotifications()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        val settingsStore = SettingsStore(this)

        // Проверка автоподключения (по умолчанию ВЫКЛЮЧЕНО)
        lifecycleScope.launch {
            val autoStart = settingsStore.autoStartOnBoot.first()
            if (autoStart && !TunnelManager.running.value && !TunnelManager.isConnecting.value) {
                prepareVpnThen { TunnelControl.startFromSavedSettings(applicationContext) }
            }
        }

        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "dark")
            val isDynamicColor by settingsStore.isDynamicColor.collectAsStateWithLifecycle(initialValue = false)
            val themePalette by settingsStore.themePalette.collectAsStateWithLifecycle(initialValue = "indigo")

            var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
            var showBatteryDialog by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val prefs = getSharedPreferences("moschata_ui_prefs", MODE_PRIVATE)
                val alreadyPrompted = prefs.getBoolean("battery_prompt_shown", false)
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!alreadyPrompted && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    kotlinx.coroutines.delay(1500)
                    if (pendingRelease == null) {
                        showBatteryDialog = true
                        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val release = fetchLatestReleaseInfo("v${BuildConfig.VERSION_NAME}", includePrerelease = false)
                    if (release != null && isNewerVersion("v${BuildConfig.VERSION_NAME}", release.versionTag, includePrerelease = false)) {
                        pendingRelease = release
                    }
                } catch (_: Exception) {}
            }

            WDTTTheme(
                themeMode = themeMode,
                dynamicColor = isDynamicColor,
                themePalette = themePalette
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        settingsStore = settingsStore,
                        onToggleConnection = { toggleConnection() },
                        onScanQr = { startQrScanner() },
                        onShowUpdateDialog = { release -> pendingRelease = release }
                    )
                }

                if (showBatteryDialog) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog = false },
                        icon = { Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = { Text("Фоновый режим работы") },
                        text = {
                            Text("Чтобы система не отключала Moschata VPN при блокировке экрана и не разрывала соединение, отключите ограничение батареи (выберите «Нет ограничений»).")
                        },
                        confirmButton = {
                            Button(onClick = {
                                showBatteryDialog = false
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    }
                                    batteryLauncher.launch(intent)
                                } catch (_: Exception) {}
                            }) {
                                Text("Настроить")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBatteryDialog = false }) {
                                Text("Позже")
                            }
                        }
                    )
                }

                pendingRelease?.let { release ->
                    AppUpdateDialog(
                        release = release,
                        onPostpone = { pendingRelease = null },
                        onUpdate = {
                            val url = release.releaseUrl.ifBlank { release.downloadUrl }
                            pendingRelease = null
                            if (!url.isNullOrBlank()) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Не удалось открыть браузер: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestNotifications() {
        NotificationHelper.ensureTunnelChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
