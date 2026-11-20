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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val autoConnect by settingsStore.autoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val connectionMode by settingsStore.connectionMode.collectAsStateWithLifecycle(initialValue = SettingsStore.CONNECTION_MODE_VPN)
    val workersPerHash by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 9)
    val vkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val hashCount = remember(vkHashes) {
        val count = vkHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() }.size
        count.coerceIn(1, SettingsStore.MAX_VK_HASHES)
    }
    val workerOptions = remember(hashCount) {
        (1..(hashCount * 3)).map { it * 9 }
    }
    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(initialValue = "udp")
    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val peer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBattery by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    var isCheckingUpdates by remember { mutableStateOf(false) }

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

            // Секция 4: Параметры обхода Белого Списка (qWDTT)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Text(
                            text = "Обход Белого Списка (qWDTT)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Настройки маскировки интернет-трафика под звонки VK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 1. Режим протокола обхода (Raw / WireGuard)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Режим протокола", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = connectionMode == SettingsStore.CONNECTION_MODE_VPN,
                                onClick = {
                                    scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_VPN) }
                                },
                                label = { Text("WireGuard (56000)") }
                            )
                            FilterChip(
                                selected = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN,
                                onClick = {
                                    scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_RAWTUN) }
                                },
                                label = { Text("Raw IP (56003)") }
                            )
                        }
                        Text(
                            text = if (connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN)
                                "Raw IP: сырые пакеты напрямую в TUN без WireGuard. Максимальная маскировка при фильтрации."
                            else
                                "WireGuard: скоростной шифрованный туннель через VK TURN (DTLS). Быстрый стандартный режим.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // 2. Мощность (число параллельных потоков)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Мощность обхода", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "$workersPerHash потоков",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
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
                                    label = { Text("$count потоков") }
                                )
                            }
                        }
                        Text(
                            text = if (hashCount > 1) {
                                "Обнаружено $hashCount VK-хеша (максимум до ${hashCount * 27} потоков). Потоки распределяются поровну, сохраняя лимиты звонков VK."
                            } else {
                                "TURN-серверы VK выделяют сокеты пачками по 9 на сессию. 9 — 1 группа (стандарт), 18 — 2 группы (турбо), 27 — 3 группы (максимум для 1 хеша)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // 3. Транспорт VK TURN (UDP / TCP)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Транспорт VK TURN", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = turnTransport != SettingsStore.TURN_TRANSPORT_TCP,
                                onClick = {
                                    scope.launch { settingsStore.saveTurnTransport(SettingsStore.TURN_TRANSPORT_UDP) }
                                },
                                label = { Text("UDP (Быстрый)") }
                            )
                            FilterChip(
                                selected = turnTransport == SettingsStore.TURN_TRANSPORT_TCP,
                                onClick = {
                                    scope.launch { settingsStore.saveTurnTransport(SettingsStore.TURN_TRANSPORT_TCP) }
                                },
                                label = { Text("TCP / 443 (Надёжный)") }
                            )
                        }
                        Text(
                            text = if (turnTransport == SettingsStore.TURN_TRANSPORT_TCP)
                                "TCP (443): передача трафика по TCP, полезно если оператор глушит нестандартный UDP."
                            else
                                "UDP: минимальный пинг и задержка при обычном обходе Белого Списка.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Секция 5: Диагностика и обновление
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
                                        Toast.makeText(context, "Доступна новая версия: ${release.versionTag}", Toast.LENGTH_LONG).show()
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
        }
    }
}
