package com.wdtt.client.ui.detector

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.detector.DetectionReport
import com.wdtt.client.detector.ProbeResult
import com.wdtt.client.detector.WhiteListDetector
import com.wdtt.client.detector.WhiteListDetectorManager

@Composable
fun WhiteListTestCard(
    modifier: Modifier = Modifier,
    onOpenDetails: () -> Unit,
    onSwitchToBypassMode: (() -> Unit)? = null
) {
    val report by WhiteListDetectorManager.report.collectAsStateWithLifecycle()
    val isChecking by WhiteListDetectorManager.isChecking.collectAsStateWithLifecycle()

    val cardColor = when (report?.state) {
        WhiteListDetector.NetworkState.FULL_INTERNET -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        WhiteListDetector.NetworkState.WHITELIST_ONLY -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        WhiteListDetector.NetworkState.NO_INTERNET -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    val borderColor = when (report?.state) {
        WhiteListDetector.NetworkState.FULL_INTERNET -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        WhiteListDetector.NetworkState.WHITELIST_ONLY -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Заголовок карточки
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                color = when (report?.state) {
                                    WhiteListDetector.NetworkState.FULL_INTERNET -> MaterialTheme.colorScheme.primary
                                    WhiteListDetector.NetworkState.WHITELIST_ONLY -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (report?.state) {
                                WhiteListDetector.NetworkState.FULL_INTERNET -> Icons.Rounded.CheckCircle
                                WhiteListDetector.NetworkState.WHITELIST_ONLY -> Icons.Rounded.Warning
                                WhiteListDetector.NetworkState.NO_INTERNET -> Icons.Rounded.WifiOff
                                null -> Icons.Rounded.Shield
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Тест Белого Списка (БС)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isChecking -> "Проверка доступности сети..."
                                report?.state == WhiteListDetector.NetworkState.FULL_INTERNET -> "🟢 Свободный интернет"
                                report?.state == WhiteListDetector.NetworkState.WHITELIST_ONLY -> "🔴 Активен Белый Список!"
                                report?.state == WhiteListDetector.NetworkState.NO_INTERNET -> "⚪ Связь отсутствует"
                                else -> "Проверка не выполнялась"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (report?.state) {
                                WhiteListDetector.NetworkState.FULL_INTERNET -> MaterialTheme.colorScheme.primary
                                WhiteListDetector.NetworkState.WHITELIST_ONLY -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (report != null) {
                    IconButton(
                        onClick = onOpenDetails,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "Подробности",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Поясняющий текст
            Text(
                text = when {
                    isChecking -> "Опрос Google 204, Cloudflare 1.1.1.1 и реестра социально значимых сайтов РФ..."
                    report?.state == WhiteListDetector.NetworkState.FULL_INTERNET ->
                        "Внешний интернет открыт без ограничений оператора. Режим обхода БС не требуется — используйте скоростной WireGuard."
                    report?.state == WhiteListDetector.NetworkState.WHITELIST_ONLY ->
                        "Оператор заблокировал внешний интернет! Доступны только ресурсы РФ. Включите режим «Обход БС» для свободного доступа."
                    report?.state == WhiteListDetector.NetworkState.NO_INTERNET ->
                        "Нет доступа к внешним и российским серверам. Проверьте мобильную передачу данных."
                    else ->
                        "Проверьте, действует ли сейчас ограничение «Белый Список» у вашего оператора."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Кнопки действий
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { WhiteListDetectorManager.runCheck() },
                    enabled = !isChecking,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Проверка…")
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (report == null) "Проверить сеть" else "Повторить тест")
                    }
                }

                if (report?.state == WhiteListDetector.NetworkState.WHITELIST_ONLY && onSwitchToBypassMode != null) {
                    FilledTonalButton(
                        onClick = onSwitchToBypassMode,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Обход БС")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteListDetailsSheet(
    onDismiss: () -> Unit
) {
    val report by WhiteListDetectorManager.report.collectAsStateWithLifecycle()
    val isChecking by WhiteListDetectorManager.isChecking.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Диагностика Белого Списка",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (report != null) {
                Text(
                    text = "Замер завершён за ${report!!.durationMs} мс",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Внешние зонды
                Text(
                    text = "Внешний интернет (Независимые хосты)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report!!.externalProbes.forEach { probe ->
                        ProbeItemView(probe)
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Реестр БС РФ
                Text(
                    text = "Белый Список РФ (Реестр)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    report!!.whitelistProbes.forEach { probe ->
                        ProbeItemView(probe)
                    }
                }

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "💡 Как работает тест",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Во время ограничения связи («глушилок») операторы оставляют доступ только к социально значимым ресурсам РФ (VK, Yandex, Госуслуги), блокируя все внешние IP и протоколы VPN. Если внешние хосты не отвечают, а сервисы РФ доступны — это признак Белого Списка. Режим «Обход БС» использует звонки VK TURN для выхода в открытую сеть.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "Тест ещё не запускался. Нажмите «Запустить тест» для детальной проверки сети.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { WhiteListDetectorManager.runCheck() },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Идет проверка…")
                } else {
                    Text("Запустить повторный тест")
                }
            }
        }
    }
}

@Composable
private fun ProbeItemView(probe: ProbeResult) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = probe.url.removePrefix("https://").substringBefore("/"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (probe.isSuccess) "Код: ${probe.httpCode} • ${probe.latencyMs} мс" else (probe.error ?: "Блокировка / таймаут"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probe.isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = if (probe.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (probe.isSuccess) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
