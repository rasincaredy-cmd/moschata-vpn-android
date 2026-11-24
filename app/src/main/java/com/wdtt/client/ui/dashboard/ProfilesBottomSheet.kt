package com.wdtt.client.ui.dashboard

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.ProfileUnbindHelper
import com.wdtt.client.ProfilesStore
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesBottomSheet(
    profilesStore: ProfilesStore,
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by profilesStore.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")
    val yandexDocsEnabled by settingsStore.yandexDocsEnabled.collectAsStateWithLifecycle(initialValue = false)

    var showAddDialog by remember { mutableStateOf(false) }
    var subscriptionUrlInput by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var unbindTargetProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var isUnbinding by remember { mutableStateOf(false) }
    var pcConfirmProfile by remember { mutableStateOf<ConnectionProfile?>(null) }

    val sortedProfiles = remember(profiles) {
        profiles.sortedWith(compareBy<ConnectionProfile> { it.isPcDevice }.thenBy { it.name })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Локации и серверы",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profiles.isNotEmpty()) "${profiles.size} серверов доступно" else "Нет добавленных профилей",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subscriptions.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (isSyncing) return@IconButton
                                scope.launch {
                                    isSyncing = true
                                    var updatedCount = 0
                                    for (sub in subscriptions) {
                                        val res = profilesStore.refreshSubscription(sub.id)
                                        if (res.isSuccess) updatedCount += res.getOrDefault(0)
                                    }
                                    isSyncing = false
                                    Toast.makeText(context, "Обновлено серверов: $updatedCount", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Обновить подписки")
                            }
                        }
                    }

                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Сканировать QR")
                    }

                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Добавить подписку", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (profiles.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Подписка не добавлена",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Получите персональную ссылку подписки в боте @MoschataVPN_bot и нажмите кнопку «Добавить подписку».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить подписку")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                ) {
                    items(sortedProfiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == currentProfileId
                        val isWg = profile.isWireGuard || profile.protocolType == ConnectionProfile.PROTOCOL_WIREGUARD
                        val isYd = profile.isYandexDocs || profile.protocolType == ConnectionProfile.PROTOCOL_YANDEX_DOCS
                        val isBypass = (profile.vkHashes.isNotBlank() || profile.peer.contains(":5600")) && !isYd && !isWg
                        val isPc = profile.isPcDevice

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (isPc && !isSelected) {
                                        pcConfirmProfile = profile
                                    } else {
                                        scope.launch {
                                            if (isYd && !yandexDocsEnabled) {
                                                settingsStore.saveYandexDocsEnabled(true)
                                                Toast.makeText(context, "Включён эксперимент: Яндекс.Документы", Toast.LENGTH_SHORT).show()
                                            }
                                            profilesStore.applyProfile(context, profile.id)
                                            onDismiss()
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(
                                        text = when {
                                            isYd -> "📄"
                                            isWg -> "🇳🇱"
                                            isPc -> "💻"
                                            profile.name.contains("Нидерланд", ignoreCase = true) || profile.peer.contains("48.102") || profile.peer.contains("13.143") -> "🇳🇱"
                                            else -> "🌐"
                                        },
                                        style = MaterialTheme.typography.headlineSmall
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = profile.name.ifBlank { "Сервер ${profile.peer}" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isYd) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFFFF9800).copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "Эксперимент 🧪",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFFFFB74D),
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF2196F3).copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "Яндекс.Документы",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF64B5F6),
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = when {
                                                        isWg -> Color(0xFF00E676).copy(alpha = 0.2f)
                                                        isBypass -> Color(0xFF7C4DFF).copy(alpha = 0.2f)
                                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    }
                                                ) {
                                                    Text(
                                                        text = when {
                                                            isWg -> "WireGuard 🛡️"
                                                            isBypass -> "Обход БС ⚡"
                                                            else -> "WireGuard"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = when {
                                                            isWg -> Color(0xFF00E676)
                                                            isBypass -> Color(0xFF9D4EDD)
                                                            else -> MaterialTheme.colorScheme.primary
                                                        },
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isPc) Color(0xFF607D8B).copy(alpha = 0.2f) else Color(0xFF009688).copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = if (isPc) "Компьютер 💻" else "Телефон 📱",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isPc) Color(0xFFB0BEC5) else Color(0xFF80CBC4),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isBypass && profile.password.isNotBlank()) {
                                        IconButton(
                                            onClick = { unbindTargetProfile = profile },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.LinkOff,
                                                contentDescription = "Отвязать устройство",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Выбран",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить подписку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Вставьте ссылку подписки из Telegram-бота @MoschataVPN_bot:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = subscriptionUrlInput,
                        onValueChange = { subscriptionUrlInput = it },
                        placeholder = { Text("https://.../sub/token") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 130.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotBlank()) subscriptionUrlInput = text.trim()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Вставить из буфера")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = subscriptionUrlInput.trim()
                        if (url.isNotEmpty()) {
                            scope.launch {
                                val res = profilesStore.addSubscription(url)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Подписка успешно подключена!", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                } else {
                                    Toast.makeText(context, "Ошибка: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Подключить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (pcConfirmProfile != null) {
        val target = pcConfirmProfile!!
        AlertDialog(
            onDismissRequest = { pcConfirmProfile = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("💻", style = MaterialTheme.typography.titleLarge)
                    Text("Профиль для ПК", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Профиль «${target.name}» предназначен для компьютера (ПК).\n\n" +
                    "Если вы подключитесь к нему со смартфона, сервер может сбросить привязку ПК или ответить ошибкой «Неверный пароль».\n\n" +
                    "Всё равно переключиться на этот профиль?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pcConfirmProfile = null
                        scope.launch {
                            val isYd = target.isYandexDocs || target.protocolType == ConnectionProfile.PROTOCOL_YANDEX_DOCS
                            if (isYd && !yandexDocsEnabled) {
                                settingsStore.saveYandexDocsEnabled(true)
                                Toast.makeText(context, "Включён эксперимент: Яндекс.Документы", Toast.LENGTH_SHORT).show()
                            }
                            profilesStore.applyProfile(context, target.id)
                            onDismiss()
                        }
                    }
                ) {
                    Text("Подключить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pcConfirmProfile = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (unbindTargetProfile != null) {
        val target = unbindTargetProfile!!
        AlertDialog(
            onDismissRequest = { if (!isUnbinding) unbindTargetProfile = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text("Отвязать устройство", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Сбросить привязку устройства для профиля «${target.name}»?\n\n" +
                    "Сервер очистит запомненный идентификатор устройства (Device ID). Это решает проблему неверного пароля при переносе профиля или смене приложения.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isUnbinding = true
                            val subUrl = subscriptions.find { it.groupId == target.groupId }?.url
                            val res = ProfileUnbindHelper.unbindProfile(context, target, subscriptionUrl = subUrl)
                            isUnbinding = false
                            unbindTargetProfile = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "✅ Привязка устройства успешно сброшена!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "❌ Ошибка: ${res.exceptionOrNull()?.message ?: "Сбой"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isUnbinding
                ) {
                    if (isUnbinding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Отвязать", color = MaterialTheme.colorScheme.onError)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { unbindTargetProfile = null },
                    enabled = !isUnbinding
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}
