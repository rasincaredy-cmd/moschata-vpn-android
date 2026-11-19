package com.wdtt.client.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.AppLinks
import com.wdtt.client.BuildConfig
import com.wdtt.client.SettingsStore
import com.wdtt.client.fetchLatestReleaseInfo
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

            // Секция 3: Опции подключения (Для опытных)
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

                    // Заводские порты
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Сервер Нидерланды (по умолчанию)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "WireGuard: порт 585 • DTLS: 56000 • RAW: 56003",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Секция 4: Диагностика и обновление
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
                                val release = fetchLatestReleaseInfo("v${BuildConfig.VERSION_NAME}", includePrerelease = false)
                                isCheckingUpdates = false
                                if (release != null) {
                                    Toast.makeText(context, "Доступно обновление: ${release.versionTag}!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "У вас установлена последняя версия (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Проверка…")
                        } else {
                            Icon(Icons.Filled.Update, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Проверить обновление (v${BuildConfig.VERSION_NAME})")
                        }
                    }
                }
            }
        }
    }
}
