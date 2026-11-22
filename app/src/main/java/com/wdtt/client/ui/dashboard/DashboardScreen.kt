package com.wdtt.client.ui.dashboard

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.*
import com.wdtt.client.detector.WhiteListDetector
import com.wdtt.client.detector.WhiteListDetectorManager
import com.wdtt.client.ui.detector.WhiteListDetailsSheet
import com.wdtt.client.ui.detector.WhiteListTestCard
import com.wdtt.client.ui.ExceptionsTab
import com.wdtt.client.ui.LogsTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settingsStore: SettingsStore,
    onToggleConnection: () -> Unit,
    onScanQr: () -> Unit,
    onShowUpdateDialog: (com.wdtt.client.AppReleaseInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profilesStore = remember { ProfilesStore(context) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val tunnelReconnecting by TunnelManager.isReconnecting.collectAsStateWithLifecycle()

    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val currentHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())

    val activeProfile = remember(profiles, currentProfileId) { profiles.find { it.id == currentProfileId } }
    val isYandexDocs = activeProfile?.isYandexDocs == true || activeProfile?.protocolType == ConnectionProfile.PROTOCOL_YANDEX_DOCS || currentProfileName.contains("Яндекс", ignoreCase = true)
    val isBypass = (currentHashes.isNotBlank() || currentPeer.contains(":5600")) && !isYandexDocs
    val hasActiveServer = (currentProfileName.isNotBlank() || currentPeer.isNotBlank()) && profiles.isNotEmpty()

    val visualState = when {
        tunnelConnecting || tunnelReconnecting -> ConnectionVisualState.CONNECTING
        tunnelRunning && isBypass -> ConnectionVisualState.CONNECTED_BYPASS
        tunnelRunning -> ConnectionVisualState.CONNECTED_WIREGUARD
        else -> ConnectionVisualState.IDLE
    }

    var showProfilesSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showWhiteListDetails by rememberSaveable { mutableStateOf(false) }

    // Полноэкранные вложенные просмотры
    var currentOverlayScreen by rememberSaveable { mutableStateOf<String?>(null) }

    // Таймер сессии
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tunnelRunning) {
        if (tunnelRunning) {
            val start = System.currentTimeMillis()
            while (tunnelRunning) {
                sessionSeconds = (System.currentTimeMillis() - start) / 1000L
                delay(1000)
            }
        } else {
            sessionSeconds = 0L
        }
    }

    val formattedDuration = remember(sessionSeconds) {
        val hours = sessionSeconds / 3600
        val mins = (sessionSeconds % 3600) / 60
        val secs = sessionSeconds % 60
        if (hours > 0) String.format("%02d:%02d:%02d", hours, mins, secs)
        else String.format("%02d:%02d", mins, secs)
    }

    if (currentOverlayScreen == "exceptions") {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Исключения приложений") },
                    navigationIcon = {
                        IconButton(onClick = { currentOverlayScreen = null }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                ExceptionsTab()
            }
        }
        return
    }

    if (currentOverlayScreen == "logs") {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Журнал работы") },
                    navigationIcon = {
                        IconButton(onClick = { currentOverlayScreen = null }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                LogsTab()
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🦫", fontSize = 24.sp)
                        Text(
                            text = "Moschata VPN",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ═══ Hero Connection Block ═══
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HeroConnectButton(
                    visualState = visualState,
                    onClick = {
                        if (!hasActiveServer && !tunnelRunning) {
                            Toast.makeText(context, "Сначала добавьте подписку или выберите сервер", Toast.LENGTH_SHORT).show()
                            showProfilesSheet = true
                        } else {
                            onToggleConnection()
                        }
                    }
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = when (visualState) {
                            ConnectionVisualState.IDLE -> "Нажмите для подключения"
                            ConnectionVisualState.CONNECTING -> "Установка соединения…"
                            ConnectionVisualState.CONNECTED_WIREGUARD -> "Защищено • Прямой туннель"
                            ConnectionVisualState.CONNECTED_BYPASS -> "Защищено • Обход Белого Списка"
                            ConnectionVisualState.DISCONNECTING -> "Отключение…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (visualState) {
                            ConnectionVisualState.CONNECTED_WIREGUARD -> Color(0xFF00E676)
                            ConnectionVisualState.CONNECTED_BYPASS -> Color(0xFF9D4EDD)
                            ConnectionVisualState.CONNECTING -> Color(0xFFFFA726)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (tunnelRunning) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "Сессия: $formattedDuration",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══ Селектор сервера (Pill Card) ═══
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { showProfilesSheet = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = when {
                                isYandexDocs -> "📄"
                                hasActiveServer -> "🇳🇱"
                                else -> "🌐"
                            },
                            fontSize = 22.sp
                        )
                        Column {
                            Text(
                                text = if (hasActiveServer) currentProfileName.ifBlank { "Сервер WireGuard" } else "Сервер не выбран",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (hasActiveServer) {
                                    when {
                                        isYandexDocs -> "Режим: Эксперимент (Яндекс.Документы) 🧪"
                                        isBypass -> "Режим: Обход БС (VK TURN) ⚡"
                                        else -> "Режим: Скоростной WireGuard"
                                    }
                                } else {
                                    "Нажмите, чтобы добавить сервер или подписку"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    hasActiveServer && isYandexDocs -> Color(0xFFFFB74D)
                                    hasActiveServer && isBypass -> Color(0xFF9D4EDD)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }

                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Сменить сервер",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ═══ Виджет Детектора Белого Списка ═══
            WhiteListTestCard(
                onOpenDetails = { showWhiteListDetails = true },
                onSwitchToBypassMode = { showProfilesSheet = true }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ═══ Bottom Sheets ═══
    if (showProfilesSheet) {
        ProfilesBottomSheet(
            profilesStore = profilesStore,
            settingsStore = settingsStore,
            onDismiss = { showProfilesSheet = false },
            onScanQr = onScanQr
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settingsStore = settingsStore,
            onDismiss = { showSettingsSheet = false },
            onOpenExceptions = {
                showSettingsSheet = false
                currentOverlayScreen = "exceptions"
            },
            onOpenLogs = {
                showSettingsSheet = false
                currentOverlayScreen = "logs"
            },
            onShowUpdateDialog = { release ->
                showSettingsSheet = false
                onShowUpdateDialog(release)
            }
        )
    }

    if (showWhiteListDetails) {
        WhiteListDetailsSheet(
            onDismiss = { showWhiteListDetails = false }
        )
    }
}
