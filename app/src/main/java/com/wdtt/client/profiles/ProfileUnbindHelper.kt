package com.wdtt.client

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ProfileUnbindHelper {

    suspend fun unbindProfile(
        context: Context,
        profile: ConnectionProfile,
        subscriptionUrl: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 1. Если профиль получен из ссылки подписки — вызываем защищённый эндпоинт бота
        val subUrl = subscriptionUrl?.takeIf { it.isNotBlank() }
        if (subUrl != null && subUrl.startsWith("http", ignoreCase = true)) {
            val unbindEndpoint = if (subUrl.endsWith("/")) "${subUrl}unbind" else "$subUrl/unbind"
            val res = callBotSubUnbind(unbindEndpoint, profile.password)
            if (res.isSuccess) {
                return@withContext Result.success(Unit)
            }
        }

        // 2. Резервный путь: прямой вызов контрольного challenge/unbind API сервера qWDTT
        val host = PeerAddress.host(profile.peer)
        val password = profile.password
        if (host.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Не указан адрес сервера или пароль профиля"))
        }

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "android_${profile.id.take(8)}"

        val managementPort = 56000
        val endpoint = "http://${if (host.contains(':')) "[$host]" else host}:$managementPort"

        try {
            val nonce = requestChallenge(endpoint)
                ?: return@withContext Result.failure(IllegalStateException("Сервер не ответил на challenge"))

            val url = URL("$endpoint/api/profile/unbind")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postData = profileProofBody("unbind", password, deviceId, nonce)
            conn.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Result.success(Unit)
            } else if (code == 401 || code == 403) {
                Result.failure(IllegalStateException("Неверный пароль профиля или доступ отозван"))
            } else {
                Result.failure(IllegalStateException("Ошибка сервера: HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callBotSubUnbind(endpoint: String, password: String): Result<Unit> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val jsonPayload = JSONObject().apply {
                put("password", password)
            }.toString()

            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code in 200..299) {
                Result.success(Unit)
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val msg = runCatching { JSONObject(errBody).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun requestChallenge(endpoint: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$endpoint/api/profile/challenge")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
            }
            conn.outputStream.use { it.write(ByteArray(0)) }
            if (conn.responseCode != 200) null
            else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("nonce").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun profileProofBody(action: String, password: String, deviceId: String, nonce: String): String {
        val idHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("WDTT-PROFILE-ID-v1\u0000" + password).toByteArray(Charsets.UTF_8))
        val keyId = idHash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val proof = mac.doFinal("$action\n$deviceId\n$nonce".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "device_id=${URLEncoder.encode(deviceId, "UTF-8")}" +
            "&nonce=${URLEncoder.encode(nonce, "UTF-8")}" +
            "&key_id=$keyId&proof=$proof"
    }
}
