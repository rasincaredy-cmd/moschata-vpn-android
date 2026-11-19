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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wdtt.client.ui.AppUpdateDialog
import com.wdtt.client.ui.dashboard.DashboardScreen
import com.wdtt.client.ui.theme.WDTTTheme
import com.wdtt.client.update.AppReleaseInfo
import com.wdtt.client.update.AppUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
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
            prepareVpnThen {
                TunnelControl.startFromSavedSettings(applicationContext)
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
            set(_) {}

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
                    prepareVpnThen { TunnelControl.startFromSavedSettings(applicationContext) }
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

            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }

            // Проверка обновлений в фоне
            LaunchedEffect(Unit) {
                try {
                    val release = AppUpdate.checkGithubRelease(includeBeta = false)
                    if (release != null) {
                        pendingRelease = release
                    }
                } catch (_: Exception) {}
            }

            WDTTTheme(
                darkTheme = darkTheme,
                dynamicColor = isDynamicColor,
                palette = themePalette
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        settingsStore = settingsStore,
                        onToggleConnection = { toggleConnection() },
                        onScanQr = { startQrScanner() }
                    )
                }

                pendingRelease?.let { release ->
                    AppUpdateDialog(
                        release = release,
                        onDismiss = { pendingRelease = null },
                        onDownloadAndInstall = {
                            AppUpdate.downloadAndInstallApk(this@MainActivity, release.apkUrl, release.tagName)
                            pendingRelease = null
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
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    private fun checkAndRequestBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (_: Exception) {}
        }
    }
}
