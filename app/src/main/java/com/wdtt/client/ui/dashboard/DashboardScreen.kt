package com.wdtt.client.ui.dashboard

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.*
import com.wdtt.client.detector.WhiteListDetectorManager
import com.wdtt.client.ui.ExceptionsTab
import com.wdtt.client.ui.LogsTab
import com.wdtt.client.ui.detector.WhiteListDetailsSheet
import com.wdtt.client.ui.detector.WhiteListTestCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DashboardVpnMode {
    REGULAR,
    BYPASS,
    YANDEX
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settingsStore: SettingsStore,
    onToggleConnection: () -> Unit,
    onScanQr: () -> Unit,
    onShowUpdateDialog: (AppReleaseInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profilesStore = remember { ProfilesStore(context) }

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val tunnelReconnecting by TunnelManager.isReconnecting.collectAsStateWithLifecycle()
    val tunnelStats by TunnelManager.stats.collectAsStateWithLifecycle()
    val lastFatalError by TunnelManager.lastFatalError.collectAsStateWithLifecycle()

    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val currentHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")
    val activeProtocolType by settingsStore.activeProtocolType.collectAsStateWithLifecycle(initialValue = ConnectionProfile.PROTOCOL_QWDTT)
    val yandexDocsEnabled by settingsStore.yandexDocsEnabled.collectAsStateWithLifecycle(initialValue = false)

    // Настройки обхода для контекстной панели прямо на главном экране
    val connectionMode by settingsStore.connectionMode.collectAsStateWithLifecycle(initialValue = SettingsStore.CONNECTION_MODE_VPN)
    val workersPerHash by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 9)
    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(initialValue = SettingsStore.TURN_TRANSPORT_UDP)
    val captchaMode by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
    val captchaSolveMethod by settingsStore.captchaSolveMethod.collectAsStateWithLifecycle(initialValue = "auto")

    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by profilesStore.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())
    val developerModeEnabled by settingsStore.developerModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val devAllowBypassOnFreeNet by settingsStore.devAllowBypassOnFreeNet.collectAsStateWithLifecycle(initialValue = false)
    val devOverrideFreeNet = developerModeEnabled && devAllowBypassOnFreeNet
    val whitelistReport by WhiteListDetectorManager.report.collectAsStateWithLifecycle()
    val whitelistChecking by WhiteListDetectorManager.isChecking.collectAsStateWithLifecycle()
    val isBypassBlockedByFreeInternet = WhiteListDetectorManager.isBypassBlockedByFreeInternet(whitelistReport, devOverrideFreeNet)

    val activeProfile = remember(profiles, currentProfileId) { profiles.find { it.id == currentProfileId } }

    val wgProfiles = remember(profiles) {
        profiles.filter { it.isWireGuard }.sortedBy { it.isPcDevice }
    }
    val bypassProfiles = remember(profiles) {
        profiles.filter { !it.isWireGuard && !it.isYandexDocs }.sortedBy { it.isPcDevice }
    }
    val yandexProfiles = remember(profiles) {
        profiles.filter { it.isYandexDocs }.sortedBy { it.isPcDevice }
    }

    val selectedMode = remember(activeProfile, activeProtocolType, currentPeer, currentHashes, wgProfiles, bypassProfiles) {
        when {
            activeProfile?.isWireGuard == true || activeProtocolType == ConnectionProfile.PROTOCOL_WIREGUARD ->
                DashboardVpnMode.REGULAR
            activeProfile?.isYandexDocs == true || activeProtocolType == ConnectionProfile.PROTOCOL_YANDEX_DOCS ->
                DashboardVpnMode.YANDEX
            bypassProfiles.isNotEmpty() || currentHashes.isNotBlank() || currentPeer.contains(":5600") ->
                DashboardVpnMode.BYPASS
            wgProfiles.isNotEmpty() ->
                DashboardVpnMode.REGULAR
            else ->
                DashboardVpnMode.BYPASS
        }
    }

    val isRegularMode = selectedMode == DashboardVpnMode.REGULAR
    val isYandexMode = selectedMode == DashboardVpnMode.YANDEX
    val isBypassMode = selectedMode == DashboardVpnMode.BYPASS
    val hasActiveServer = activeProfile != null || currentProfileName.isNotBlank() || currentPeer.isNotBlank()

    val visualState = when {
        tunnelConnecting || tunnelReconnecting -> ConnectionVisualState.CONNECTING
        tunnelRunning && isBypassMode -> ConnectionVisualState.CONNECTED_BYPASS
        tunnelRunning -> ConnectionVisualState.CONNECTED_WIREGUARD
        else -> ConnectionVisualState.IDLE
    }

    var showProfilesSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showWhiteListDetails by rememberSaveable { mutableStateOf(false) }
    var unbindBusy by remember { mutableStateOf(false) }
    var dismissedError by remember(lastFatalError) { mutableStateOf(false) }

    // Полноэкранные вложенные просмотры
    var currentOverlayScreen by rememberSaveable { mutableStateOf<String?>(null) }

    // Таймер сессии
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        WhiteListDetectorManager.runCheckIfUnchecked(context)
    }
    LaunchedEffect(isBypassBlockedByFreeInternet, selectedMode, tunnelRunning, tunnelConnecting, wgProfiles) {
        if (isBypassBlockedByFreeInternet &&
            selectedMode == DashboardVpnMode.BYPASS &&
            !tunnelRunning &&
            !tunnelConnecting &&
            wgProfiles.isNotEmpty()
        ) {
            profilesStore.applyProfile(context, wgProfiles.first().id)
            Toast.makeText(
                context,
                "Интернет свободен — автоматически выбран Обычный VPN",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
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

    val hashCount = remember(currentHashes) {
        val count = currentHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() }.size
        count.coerceIn(1, SettingsStore.MAX_VK_HASHES)
    }
    val workerOptions = remember(hashCount) {
        (1..(hashCount * 3)).map { it * 9 }
    }

    fun switchMode(targetMode: DashboardVpnMode) {
        if (targetMode == selectedMode && activeProfile != null && !(targetMode == DashboardVpnMode.BYPASS && isBypassBlockedByFreeInternet)) return
        if (targetMode == DashboardVpnMode.YANDEX) {
            Toast.makeText(
                context,
                "Обход через Яндекс.Документы (OpenFlux) находится в разработке",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val candidates = if (targetMode == DashboardVpnMode.REGULAR) wgProfiles else bypassProfiles
        val chosen = candidates.firstOrNull()
        if (chosen == null) {
            val msg = if (targetMode == DashboardVpnMode.REGULAR) {
                "В вашей подписке нет профиля Обычного VPN"
            } else {
                "В вашей подписке нет профиля Обхода БС"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            showProfilesSheet = true
            return
        }
        val wasRunning = tunnelRunning || tunnelConnecting
        scope.launch {
            if (targetMode == DashboardVpnMode.BYPASS) {
                if (isBypassBlockedByFreeInternet) {
                    Toast.makeText(
                        context,
                        "Интернет свободен — «Обход БС» отключён. Используйте Обычный VPN.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val rep = WhiteListDetectorManager.report.value
                if (!devOverrideFreeNet && (rep == null || rep.viaVpn)) {
                    Toast.makeText(context, "Проверка сети на Белый Список…", Toast.LENGTH_SHORT).show()
                    val gate = WhiteListDetectorManager.verifyCanStartBypass(context)
                    if (!gate.allowed) {
                        Toast.makeText(
                            context,
                            gate.message ?: "Интернет свободен — «Обход БС» отключён.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                }
            }
            if (wasRunning) {
                TunnelControl.stop(context.applicationContext)
                delay(450)
            }
            profilesStore.applyProfile(context, chosen.id)
            if (wasRunning) {
                TunnelControl.startFromSavedSettings(context.applicationContext)
                Toast.makeText(
                    context,
                    "Переподключение: ${if (targetMode == DashboardVpnMode.REGULAR) "Обычный VPN" else "Обход БС"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
                    IconButton(onClick = { showProfilesSheet = true }) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = "Серверы и подписки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // ═══ 1. Hero Connection Block ═══
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HeroConnectButton(
                    visualState = visualState,
                    onClick = {
                        if (!hasActiveServer && !tunnelRunning) {
                            Toast.makeText(context, "Сначала добавьте подписку", Toast.LENGTH_SHORT).show()
                            showProfilesSheet = true
                        } else {
                            onToggleConnection()
                        }
                    }
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when (visualState) {
                            ConnectionVisualState.IDLE -> "Нажмите для подключения"
                            ConnectionVisualState.CONNECTING -> "Установка соединения…"
                            ConnectionVisualState.CONNECTED_WIREGUARD -> "Защищено • Обычный VPN"
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Text(
                                    text = "Сессия: $formattedDuration",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            val speedBadge = remember(tunnelStats) {
                                val afterFirst = tunnelStats.substringAfter("| ", "")
                                val speedOnly = afterFirst.substringBefore(" |").trim()
                                when {
                                    speedOnly.startsWith("Скорость:", ignoreCase = true) ->
                                        speedOnly.removePrefix("Скорость:").trim()
                                    speedOnly.isNotBlank() -> speedOnly
                                    else -> tunnelStats.substringBefore(" |").trim()
                                }
                            }
                            if (speedBadge.isNotBlank() && !speedBadge.startsWith("Ожидание") && !speedBadge.startsWith("Подключение") && !speedBadge.startsWith("Активных")) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                ) {
                                    Text(
                                        text = speedBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ═══ 1b. Встроенный баннер ошибки подключения ═══
            AnimatedVisibility(visible = lastFatalError != null && !dismissedError) {
                val errText = lastFatalError.orEmpty()
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = errText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(
                                onClick = { dismissedError = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Скрыть",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (errText.contains("пароль", true) || errText.contains("mismatch", true)) {
                                OutlinedButton(
                                    onClick = {
                                        val target = activeProfile ?: return@OutlinedButton
                                        if (unbindBusy) return@OutlinedButton
                                        scope.launch {
                                            unbindBusy = true
                                            val subUrl = subscriptions.find { it.groupId == target.groupId }?.url
                                            val res = ProfileUnbindHelper.unbindProfile(context, target, subscriptionUrl = subUrl)
                                            unbindBusy = false
                                            if (res.isSuccess) {
                                                dismissedError = true
                                                Toast.makeText(context, "Устройство отвязано! Нажмите Подключить.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Ошибка отвязки: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(if (unbindBusy) "Сброс..." else "Отвязать устройство", fontSize = 12.sp)
                                }
                            }
                            if (errText.contains("AmneziaWG", true) && bypassProfiles.isNotEmpty() && !isBypassBlockedByFreeInternet) {
                                Button(
                                    onClick = {
                                        dismissedError = true
                                        switchMode(DashboardVpnMode.BYPASS)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Переключить на Обход БС", fontSize = 12.sp)
                                }
                            }
                            if ((errText.contains("Обход БС", true) || errText.contains("свободен", true)) && wgProfiles.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        dismissedError = true
                                        switchMode(DashboardVpnMode.REGULAR)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Обычный VPN", fontSize = 12.sp)
                                }
                            }
                            if (errText.contains("Белый Список", true) || errText.contains("свободен", true)) {
                                OutlinedButton(
                                    onClick = { WhiteListDetectorManager.runCheck(viaVpn = false) },
                                    enabled = !whitelistChecking,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Тест БС", fontSize = 12.sp)
                                }
                            }
                            TextButton(
                                onClick = { currentOverlayScreen = "logs" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Открыть логи", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            if (profiles.isEmpty()) {
                // Приветственная карточка для нового пользователя без подписки
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Подключите подписку из бота",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Добавьте ссылку подписки из @MoschataVPN_bot, чтобы появились Обычный VPN (AmneziaWG) и Обход Белых Списков.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { showProfilesSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить подписку")
                        }
                    }
                }
            } else {
                // ═══ 2. Выбор режима подключения прямо под кнопкой ═══
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Режим работы",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModeSelectorTile(
                            modifier = Modifier.weight(1f),
                            emoji = "🛡️",
                            title = "Обычный VPN",
                            subtitle = if (wgProfiles.isNotEmpty()) "AmneziaWG" else "Недоступен",
                            selected = selectedMode == DashboardVpnMode.REGULAR,
                            available = wgProfiles.isNotEmpty(),
                            accentColor = Color(0xFF00E676),
                            onClick = { switchMode(DashboardVpnMode.REGULAR) }
                        )

                        ModeSelectorTile(
                            modifier = Modifier.weight(1f),
                            emoji = "⚡",
                            title = "Обход БС",
                            subtitle = when {
                                bypassProfiles.isEmpty() -> "Недоступен"
                                isBypassBlockedByFreeInternet -> "Сеть свободна 🔒"
                                devOverrideFreeNet && whitelistReport?.viaVpn != true &&
                                    whitelistReport?.state == com.wdtt.client.detector.WhiteListDetector.NetworkState.FULL_INTERNET -> "VK TURN (DEV)"
                                else -> "VK TURN"
                            },
                            selected = selectedMode == DashboardVpnMode.BYPASS,
                            available = bypassProfiles.isNotEmpty() && !isBypassBlockedByFreeInternet,
                            accentColor = Color(0xFF9D4EDD),
                            onClick = { switchMode(DashboardVpnMode.BYPASS) }
                        )

                        if (yandexProfiles.isNotEmpty() || yandexDocsEnabled) {
                            ModeSelectorTile(
                                modifier = Modifier.weight(1f),
                                emoji = "📄",
                                title = "Яндекс БС",
                                subtitle = "Скоро",
                                selected = selectedMode == DashboardVpnMode.YANDEX,
                                available = false,
                                accentColor = Color(0xFFFFB74D),
                                onClick = { switchMode(DashboardVpnMode.YANDEX) }
                            )
                        }
                    }
                }

                // ═══ 3. Контекстные настройки выбранного режима ═══
                AnimatedContent(
                    targetState = selectedMode,
                    label = "ModeSettingsTransition"
                ) { mode ->
                    when (mode) {
                        DashboardVpnMode.REGULAR -> {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { showProfilesSheet = true }
                                            .padding(4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("🇳🇱", fontSize = 22.sp)
                                            Column {
                                                Text(
                                                    text = activeProfile?.name ?: currentProfileName.ifBlank { "Сервер AmneziaWG" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Быстрый прямой туннель с обфускацией",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF00E676)
                                                )
                                            }
                                        }
                                        Icon(
                                            Icons.Filled.KeyboardArrowRight,
                                            contentDescription = "Выбрать сервер",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { currentOverlayScreen = "exceptions" }
                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.CallSplit,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Раздельное туннелирование (Банки / РФ)",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        DashboardVpnMode.BYPASS, DashboardVpnMode.YANDEX -> {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, Color(0xFF9D4EDD).copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (isBypassBlockedByFreeInternet) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "🔒 Обход БС заблокирован: интернет свободен",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    onClick = { WhiteListDetectorManager.runCheck(viaVpn = false) },
                                                    enabled = !whitelistChecking,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Тест БС", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }

                                    // Сервер и кнопка отвязки
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { showProfilesSheet = true }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Text(if (activeProfile?.isPcDevice == true) "💻" else "⚡", fontSize = 20.sp)
                                            Column {
                                                Text(
                                                    text = activeProfile?.name ?: currentProfileName.ifBlank { "Профиль Обхода БС" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Нажмите для смены профиля/устройства",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (activeProfile != null && activeProfile.password.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    if (unbindBusy) return@IconButton
                                                    scope.launch {
                                                        unbindBusy = true
                                                        val subUrl = subscriptions.find { it.groupId == activeProfile.groupId }?.url
                                                        val res = ProfileUnbindHelper.unbindProfile(context, activeProfile, subscriptionUrl = subUrl)
                                                        unbindBusy = false
                                                        if (res.isSuccess) {
                                                            Toast.makeText(context, "Устройство отвязано от сервера!", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Ошибка отвязки: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                if (unbindBusy) {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(
                                                        Icons.Filled.LinkOff,
                                                        contentDescription = "Отвязать устройство",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                                    // Прохождение капчи VK
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Капча VK",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val isManual = captchaMode == "wv" && captchaSolveMethod == "manual"
                                            val isRjs = captchaMode == "rjs"
                                            val isAuto = !isManual && !isRjs

                                            FilterChip(
                                                selected = isAuto,
                                                onClick = {
                                                    scope.launch {
                                                        settingsStore.saveCaptchaMode("auto")
                                                        settingsStore.saveCaptchaSolveMethod("auto")
                                                    }
                                                },
                                                label = { Text("Авто") }
                                            )
                                            FilterChip(
                                                selected = isManual,
                                                onClick = {
                                                    scope.launch {
                                                        settingsStore.saveCaptchaMode("wv")
                                                        settingsStore.saveCaptchaSolveMethod("manual")
                                                    }
                                                },
                                                label = { Text("Вручную (окно)") }
                                            )
                                            FilterChip(
                                                selected = isRjs,
                                                onClick = {
                                                    scope.launch {
                                                        settingsStore.saveCaptchaMode("rjs")
                                                    }
                                                },
                                                label = { Text("Без браузера (Go)") }
                                            )
                                        }
                                    }

                                    // Мощность (число потоков)
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Мощность потоков",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "$workersPerHash потоков",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF9D4EDD)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            workerOptions.forEach { count ->
                                                FilterChip(
                                                    selected = workersPerHash == count,
                                                    onClick = { scope.launch { settingsStore.saveWorkersPerHash(count) } },
                                                    label = { Text("$count") }
                                                )
                                            }
                                        }
                                    }

                                    // Протокол и транспорт обхода
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Режим обхода и транспорт",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChip(
                                                selected = connectionMode == SettingsStore.CONNECTION_MODE_VPN,
                                                onClick = { scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_VPN) } },
                                                label = { Text("WireGuard (56000)") }
                                            )
                                            FilterChip(
                                                selected = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN,
                                                onClick = { scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_RAWTUN) } },
                                                label = { Text("Raw IP (56003)") }
                                            )
                                            FilterChip(
                                                selected = turnTransport == SettingsStore.TURN_TRANSPORT_TCP,
                                                onClick = {
                                                    val next = if (turnTransport == SettingsStore.TURN_TRANSPORT_TCP) {
                                                        SettingsStore.TURN_TRANSPORT_UDP
                                                    } else {
                                                        SettingsStore.TURN_TRANSPORT_TCP
                                                    }
                                                    scope.launch { settingsStore.saveTurnTransport(next) }
                                                },
                                                label = {
                                                    Text(if (turnTransport == SettingsStore.TURN_TRANSPORT_TCP) "TURN: TCP 443" else "TURN: UDP")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ═══ 4. Виджет Детектора Белого Списка ═══
            WhiteListTestCard(
                onOpenDetails = { showWhiteListDetails = true },
                onSwitchToBypassMode = { switchMode(DashboardVpnMode.BYPASS) }
            )

            Spacer(modifier = Modifier.height(12.dp))
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

@Composable
private fun ModeSelectorTile(
    modifier: Modifier = Modifier,
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    available: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = when {
            selected -> accentColor.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = when {
                selected -> accentColor
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            }
        ),
        modifier = modifier
            .alpha(if (available) 1f else 0.52f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(emoji, fontSize = 20.sp)
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
