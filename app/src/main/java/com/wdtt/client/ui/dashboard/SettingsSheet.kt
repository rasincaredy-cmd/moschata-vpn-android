package com.wdtt.client.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.AppLinks
import com.wdtt.client.BuildConfig
import com.wdtt.client.SettingsStore
import com.wdtt.client.fetchLatestReleaseInfo
import com.wdtt.client.isNewerVersion
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenLogs: () -> Unit,
    onShowUpdateDialog: (com.wdtt.client.AppReleaseInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val autoConnect by settingsStore.autoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val extendedNotificationStats by settingsStore.extendedNotificationStats.collectAsStateWithLifecycle(initialValue = false)
    val yandexDocsEnabled by settingsStore.yandexDocsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val developerModeEnabled by settingsStore.developerModeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val devAllowBypassOnFreeNet by settingsStore.devAllowBypassOnFreeNet.collectAsStateWithLifecycle(initialValue = false)
    val goDnsPreset by settingsStore.goDnsPreset.collectAsStateWithLifecycle(initialValue = "yandex")
    val detailedLogs by settingsStore.detailedLogs.collectAsStateWithLifecycle(initialValue = false)
    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val peer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBattery by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTapAtMs by remember { mutableLongStateOf(0L) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Секция 1: Сервис и бот
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Moschata VPN",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Официальный клиент бота @MoschataVPN_bot. Управление подпиской и смена серверов происходят через Telegram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.TELEGRAM_BOT))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть @MoschataVPN_bot")
                    }
                }
            }

            // Секция 2: Раздельное туннелирование
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onOpenExceptions() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Раздельное туннелирование",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Исключить приложения (банки, Госуслуги), чтобы они работали напрямую без VPN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Секция 3: Опции подключения
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Параметры подключения",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Автоподключение при входе (по дефолту выключено)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Автоподключение при старте", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Подключать VPN автоматически при запуске приложения",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { checked ->
                                scope.launch { settingsStore.saveAutoStartOnBoot(checked) }
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Подробная статистика в уведомлении
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Подробная статистика в уведомлении", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Показывать число потоков и детальный счётчик ↓ / ↑ в шторке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = extendedNotificationStats,
                            onCheckedChange = { checked ->
                                scope.launch { settingsStore.saveExtendedNotificationStats(checked) }
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Фоновая работа и батарея
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Фоновый режим (Батарея)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (isIgnoringBattery) "Ограничения отключены (фоновый режим активен) ✓" else "Включена оптимизация (нажмите, чтобы разрешить фоновую работу) ⚠️",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Icon(
                            Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = if (isIgnoringBattery) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Активный сервер
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (currentProfileName.isNotBlank()) "Сервер: $currentProfileName" else "Сервер не выбран",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (peer.isNotBlank()) "Адрес узла: $peer" else "Добавьте персональную подписку во вкладке «Локации и серверы»",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Секция 4: Сеть и диагностика (тонкие настройки обхода теперь живут на главном экране)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Сеть и диагностика",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // DNS резолвер
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DNS для подключения", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "yandex" to "Яндекс",
                                "doh-yandex" to "DoH Яндекс",
                                "cloudflare" to "Cloudflare",
                                "google" to "Google",
                                "operator" to "Операторский"
                            ).forEach { (key, title) ->
                                FilterChip(
                                    selected = goDnsPreset == key,
                                    onClick = { scope.launch { settingsStore.saveGoDns(key) } },
                                    label = { Text(title) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Подробные логи диагностики
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Подробный журнал диагностики", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Показывать технические события DNS, RAW TUN и капчи",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = detailedLogs,
                            onCheckedChange = { checked ->
                                scope.launch { settingsStore.saveDetailedLogs(checked) }
                            }
                        )
                    }
                }
            }

            // Секция 5: Экспериментальные функции
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Экспериментальные функции",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFF9800).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "🧪 BETA",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFB74D),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Резервное подключение через Яндекс.Документы", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Маскировка трафика через протокол OpenFlux и защищенный WSS-канал docs.yandex.ru:443",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = yandexDocsEnabled,
                            onCheckedChange = { checked ->
                                scope.launch { settingsStore.saveYandexDocsEnabled(checked) }
                            }
                        )
                    }

                    if (yandexDocsEnabled) {
                        Text(
                            text = "Профили с поддержкой Яндекс.Документов помечаются значком 📄 и бейджем «Эксперимент» в списке серверов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB74D)
                        )
                    }
                }
            }

            // Секция 6: Отладка и обновления
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Отладка и обновления",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenLogs,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Журнал (Логи)")
                        }

                        OutlinedButton(
                            onClick = {
                                val report = """
                                    Приложение: Moschata VPN v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})
                                    Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                                    Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
                                """.trimIndent()
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("Moschata Report", report))
                                Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Копия отчёта")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            if (isCheckingUpdates) return@OutlinedButton
                            scope.launch {
                                isCheckingUpdates = true
                                try {
                                    val release = fetchLatestReleaseInfo("v${BuildConfig.VERSION_NAME}", includePrerelease = false)
                                    isCheckingUpdates = false
                                    if (release != null && isNewerVersion("v${BuildConfig.VERSION_NAME}", release.versionTag, includePrerelease = false)) {
                                        onShowUpdateDialog(release)
                                    } else {
                                        Toast.makeText(context, "У вас установлена последняя версия (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    isCheckingUpdates = false
                                    Toast.makeText(context, "Ошибка проверки: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Проверка обновлений…")
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Проверить обновление (v${BuildConfig.VERSION_NAME})")
                        }
                    }
                }
            }

            if (developerModeEnabled) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF9D4EDD).copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🛠️ Режим разработчика",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCE93D8)
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        settingsStore.saveDeveloperModeEnabled(false)
                                        Toast.makeText(context, "Режим разработчика выключен", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Выключить", fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Игнорировать запрет Обхода БС", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Разрешить тестировать Обход БС даже при свободном интернете",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = devAllowBypassOnFreeNet,
                                onCheckedChange = { checked ->
                                    scope.launch { settingsStore.saveDevAllowBypassOnFreeNet(checked) }
                                }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        if (developerModeEnabled) {
                            Toast.makeText(context, "Режим разработчика уже активен", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        val now = System.currentTimeMillis()
                        val nextCount = if (now - lastVersionTapAtMs <= 2_500L) versionTapCount + 1 else 1
                        lastVersionTapAtMs = now
                        versionTapCount = nextCount
                        if (nextCount >= 7) {
                            versionTapCount = 0
                            scope.launch {
                                settingsStore.saveDeveloperModeEnabled(true)
                                Toast.makeText(context, "🛠️ Режим разработчика включён!", Toast.LENGTH_SHORT).show()
                            }
                        } else if (nextCount >= 4) {
                            Toast.makeText(context, "Ещё ${7 - nextCount} нажатий до режима разработчика", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Moschata VPN • v${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
        }
    }
}
