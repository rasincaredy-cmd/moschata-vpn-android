package com.wdtt.client.ui.dashboard

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
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

    var showAddDialog by remember { mutableStateOf(false) }
    var subscriptionUrlInput by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }

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
            // Заголовок и быстрые действия
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Обновить подписку
                    IconButton(
                        onClick = {
                            if (isSyncing) return@IconButton
                            scope.launch {
                                isSyncing = true
                                var updatedCount = 0
                                for (sub in subscriptions) {
                                    val res = profilesStore.syncSubscription(sub)
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
                            Icon(Icons.Rounded.Refresh, contentDescription = "Обновить подписки")
                        }
                    }

                    // Сканировать QR
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Сканировать QR")
                    }

                    // Добавить подписку
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Добавить подписку", tint = MaterialTheme.colorScheme.primary)
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
                            Icon(Icons.Rounded.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    items(profiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == currentProfileId
                        val isBypass = profile.vkHashes.isNotBlank() || profile.peer.contains(":5600")

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
                                    scope.launch {
                                        profilesStore.applyProfile(context, profile.id)
                                        onDismiss()
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
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Флаг
                                    Text(
                                        text = if (profile.name.contains("Нидерланд", ignoreCase = true) || profile.peer.contains("48.102") || profile.peer.contains("13.143")) "🇳🇱" else "🌐",
                                        style = MaterialTheme.typography.headlineSmall
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = profile.name.ifBlank { "Сервер ${profile.peer}" },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isBypass) Color(0xFF7C4DFF).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = if (isBypass) "Обход БС ⚡" else "WireGuard",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isBypass) Color(0xFF9D4EDD) else MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
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
                                            Icons.Rounded.Check,
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
                        placeholder = { Text("https://.../subscription") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
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
}
