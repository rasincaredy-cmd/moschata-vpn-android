package com.wdtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ParsedRemoteSubscription(
    val profiles: List<ConnectionProfile>,
    val subscriptionName: String? = null,
    val description: String = "",
    val trafficUsedMb: Double = 0.0,
    val trafficLimitMb: Double = 0.0,
    val updatedAt: String = "",
    val version: Int = 0
)

object SubscriptionImport {

    suspend fun fetch(url: String): Result<ParsedRemoteSubscription> = withContext(Dispatchers.IO) {
        try {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                return@withContext Result.failure(IllegalArgumentException("Адрес подписки должен начинаться с http:// или https://"))
            }
            val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "MoschataVPN-Subscription/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.failure(IllegalStateException("HTTP $code"))
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val parsed = parsePayload(body)
                ?: return@withContext Result.failure(IllegalArgumentException("Неверный формат подписки"))
            if (parsed.profiles.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("В подписке нет профилей"))
            }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parsePayload(rawText: String): ParsedRemoteSubscription? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        var jsonStr = trimmed
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{") && !trimmed.startsWith("qwdtt:")) {
            try {
                val decoded = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                jsonStr = String(decoded, Charsets.UTF_8).trim()
            } catch (_: Exception) {
            }
        }

        if (jsonStr.startsWith("qwdtt://config") || jsonStr.startsWith("qwdtt:config")) {
            val single = parseQwdttUri(jsonStr) ?: return null
            return ParsedRemoteSubscription(profiles = listOf(single), subscriptionName = single.name)
        }

        if (jsonStr.startsWith("openflux://") || jsonStr.startsWith("openflux:")) {
            val single = parseOpenFluxUri(jsonStr) ?: return null
            return ParsedRemoteSubscription(profiles = listOf(single), subscriptionName = single.name)
        }

        var description = ""
        var trafficUsed = 0.0
        var trafficLimit = 0.0
        var updatedAt = ""
        var version = 0
        var suggestedName: String? = null

        if (jsonStr.startsWith("{")) {
            try {
                val root = JSONObject(jsonStr)
                suggestedName = root.optString("subscriptionName", root.optString("groupName", "")).ifBlank { null }
                description = root.optString("description", root.optString("info", ""))
                trafficUsed = root.optDouble("trafficUsedMb", root.optDouble("trafficMb", 0.0))
                trafficLimit = root.optDouble("trafficLimitMb", root.optDouble("trafficLimit", 0.0))
                updatedAt = root.optString("updatedAt", root.optString("updated", ""))
                version = root.optInt("version", 0)

                val array = root.optJSONArray("profiles") ?: root.optJSONArray("servers")
                if (array != null) {
                    val profiles = parseProfileArray(array, suggestedName)
                    if (profiles.isNotEmpty()) {
                        return ParsedRemoteSubscription(
                            profiles = profiles,
                            subscriptionName = suggestedName,
                            description = description,
                            trafficUsedMb = trafficUsed,
                            trafficLimitMb = trafficLimit,
                            updatedAt = updatedAt,
                            version = version
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (jsonStr.startsWith("[")) {
            try {
                val array = JSONArray(jsonStr)
                if (array.length() > 0) {
                    val first = array.optJSONObject(0)
                    val gName = first?.optString("groupName", "") ?: ""
                    if (gName.isNotBlank()) suggestedName = gName
                }
                val profiles = parseProfileArray(array, suggestedName)
                if (profiles.isNotEmpty()) {
                    return ParsedRemoteSubscription(
                        profiles = profiles,
                        subscriptionName = suggestedName,
                        description = description,
                        trafficUsedMb = trafficUsed,
                        trafficLimitMb = trafficLimit,
                        updatedAt = updatedAt,
                        version = version
                    )
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun parseProfileArray(array: JSONArray, fallbackGroupName: String?): List<ConnectionProfile> {
        val list = mutableListOf<ConnectionProfile>()
        for (i in 0 until array.length()) {
            val jsonObj = array.optJSONObject(i) ?: continue
            parseProfileObject(jsonObj)?.let { list.add(it) }
        }
        return list
    }

    private fun parseProfileObject(jsonObj: JSONObject): ConnectionProfile? {
        val type = jsonObj.optString("type", "").trim().lowercase()
        val uriStr = jsonObj.optString("uri", "").trim()
        val isYandexDocs = type == "yandex_docs" || type == "openflux" || uriStr.startsWith("openflux://", ignoreCase = true) || uriStr.startsWith("openflux:", ignoreCase = true)

        var peer = jsonObj.optString("peer", "").trim()
        if (peer.isBlank() && uriStr.isNotBlank()) {
            val parsed = parseOpenFluxUri(uriStr)
            if (parsed != null) peer = parsed.peer
        }
        if (peer.isBlank() && isYandexDocs) {
            peer = "docs.yandex.ru:443"
        }
        if (peer.isBlank()) return null

        val defaultName = if (isYandexDocs) "📄 Яндекс.Документы" else "Imported"
        var name = jsonObj.optString("name", defaultName).trim()
        if (isYandexDocs && !name.contains("📄")) {
            name = "📄 $name"
        }

        val rawDevice = jsonObj.optString("device", jsonObj.optString("deviceType", "")).trim().lowercase()
        val isPc = rawDevice == "pc" || rawDevice == "windows" || rawDevice == "desktop" ||
            name.contains("комп", ignoreCase = true) ||
            name.contains("пк", ignoreCase = true) ||
            name.contains("pc", ignoreCase = true) ||
            name.contains("windows", ignoreCase = true) ||
            name.contains("десктоп", ignoreCase = true) ||
            name.contains("ноутбук", ignoreCase = true)
        val deviceType = if (isPc) ConnectionProfile.DEVICE_PC else ConnectionProfile.DEVICE_PHONE

        // Нормализация порта для qWDTT: если в ссылке зашит 56003 (raw-порт), для базового профиля
        // используем стандартный 56000 (DTLS), а на 56003 клиент переключается сам при выборе режима Raw.
        if (!isYandexDocs && peer.endsWith(":56003")) {
            peer = peer.replace(":56003", ":56000")
        }

        val hashes = jsonObj.optString("hashes", jsonObj.optString("vkHashes", ""))
        val workers = jsonObj.optInt("workers", jsonObj.optInt("workersPerHash", 9))
        val port = jsonObj.optInt("port", jsonObj.optInt("listenPort", 9000))
        var pass = jsonObj.optString("password", jsonObj.optString("pass", jsonObj.optString("token", "")))
        if (pass.isBlank() && uriStr.isNotBlank()) {
            val parsed = parseOpenFluxUri(uriStr)
            if (parsed != null && parsed.password.isNotBlank()) {
                pass = parsed.password
            }
        }

        val traffic = jsonObj.optDouble("trafficMb", jsonObj.optDouble("trafficUsedMb", 0.0))
        val serverHost = jsonObj.optString("server", "")
        val diagKey = jsonObj.optString("diagKey", jsonObj.optString("dk", serverHost)).trim()
        val protocolType = if (isYandexDocs) ConnectionProfile.PROTOCOL_YANDEX_DOCS else ConnectionProfile.PROTOCOL_QWDTT

        return ConnectionProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            peer = peer,
            vkHashes = hashes,
            workersPerHash = workers,
            listenPort = port,
            password = pass,
            trafficMb = traffic.coerceAtLeast(0.0),
            groupId = "",
            useGlobalHashes = if (isYandexDocs) false else hashes.isBlank(),
            diagKey = diagKey,
            protocolType = protocolType,
            deviceType = deviceType
        )
    }

    private fun parseOpenFluxUri(rawUri: String): ConnectionProfile? {
        val trimmed = rawUri.trim()
        return try {
            val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull()
            val uriString = trimmed.removePrefix("openflux://").removePrefix("openflux:")

            // Extract userInfo / password
            var password = uri?.userInfo.orEmpty()
            var remainder = uriString
            if (password.isEmpty() && remainder.contains("@")) {
                val atIdx = remainder.indexOf("@")
                password = remainder.substring(0, atIdx)
                remainder = remainder.substring(atIdx + 1)
            }
            if (password.isEmpty()) {
                password = uri?.getQueryParameter("password")
                    ?: uri?.getQueryParameter("pass")
                    ?: uri?.getQueryParameter("token")
                    ?: ""
            }

            // Extract host and port
            var host = uri?.host.orEmpty()
            var port = uri?.port ?: -1
            if (host.isEmpty()) {
                val slashIdx = remainder.indexOf("/")
                val hostPort = if (slashIdx != -1) remainder.substring(0, slashIdx) else remainder.substringBefore("?")
                if (hostPort.contains(":")) {
                    host = hostPort.substringBefore(":")
                    port = hostPort.substringAfter(":").toIntOrNull() ?: 443
                } else {
                    host = hostPort
                    port = 443
                }
            }
            if (host.isEmpty()) host = "docs.yandex.ru"
            if (port <= 0) port = 443
            val peer = "$host:$port"

            // Extract query parameters: server, name
            var server = uri?.getQueryParameter("server").orEmpty()
            var name = uri?.getQueryParameter("name").orEmpty()
            if (server.isEmpty() && trimmed.contains("server=")) {
                server = Regex("[?&]server=([^&]+)").find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
            }
            if (name.isEmpty() && trimmed.contains("name=")) {
                name = Regex("[?&]name=([^&]+)").find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
                name = runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
            }
            if (name.isEmpty()) {
                name = "📄 Яндекс.Документы"
            } else if (!name.startsWith("📄")) {
                name = "📄 $name"
            }

            val isPc = trimmed.contains("device=pc", ignoreCase = true) ||
                name.contains("комп", ignoreCase = true) ||
                name.contains("пк", ignoreCase = true) ||
                name.contains("pc", ignoreCase = true)
            val deviceType = if (isPc) ConnectionProfile.DEVICE_PC else ConnectionProfile.DEVICE_PHONE

            ConnectionProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                peer = peer,
                vkHashes = "",
                workersPerHash = 9,
                listenPort = 9000,
                password = password,
                trafficMb = 0.0,
                groupId = "",
                useGlobalHashes = false,
                diagKey = server,
                protocolType = ConnectionProfile.PROTOCOL_YANDEX_DOCS,
                deviceType = deviceType
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQwdttUri(trimmed: String): ConnectionProfile? {
        return try {
            val uri = android.net.Uri.parse(trimmed.replace("qwdtt:config", "qwdtt://config"))
            val name = uri.getQueryParameter("name") ?: "QR Профиль"
            val peer = uri.getQueryParameter("peer") ?: return null
            val hashes = uri.getQueryParameter("hashes") ?: ""
            val workers = uri.getQueryParameter("workers")?.toIntOrNull() ?: 9
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 9000
            val pass = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password") ?: ""
            val isPc = trimmed.contains("device=pc", ignoreCase = true) ||
                name.contains("комп", ignoreCase = true) ||
                name.contains("пк", ignoreCase = true) ||
                name.contains("pc", ignoreCase = true)
            val deviceType = if (isPc) ConnectionProfile.DEVICE_PC else ConnectionProfile.DEVICE_PHONE

            ConnectionProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                peer = peer,
                vkHashes = hashes,
                workersPerHash = workers,
                listenPort = port,
                password = pass,
                useGlobalHashes = hashes.isBlank(),
                diagKey = uri.getQueryParameter("dk").orEmpty().trim(),
                deviceType = deviceType
            )
        } catch (_: Exception) {
            null
        }
    }
}
