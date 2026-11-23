package com.wdtt.client.profiles

import android.content.Context
import android.provider.Settings
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.network.PeerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ProfileUnbindHelper {

    suspend fun unbindProfile(
        context: Context,
        profile: ConnectionProfile,
        unbindAllDevices: Boolean = true,
        managementPort: Int = 56000
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val host = PeerAddress.host(profile.peer)
        val password = profile.password
        if (host.isBlank() || password.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Не указан адрес сервера или пароль профиля"))
        }

        val deviceId = if (unbindAllDevices) {
            ""
        } else {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        }

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
                Result.failure(IllegalStateException("Неверный пароль профиля или ключ"))
            } else {
                Result.failure(IllegalStateException("Ошибка сервера: HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
        val idHash = MessageDigest.getInstance("SHA-256")
            .digest(("WDTT-PROFILE-ID-v1\u0000" + password).toByteArray(Charsets.UTF_8))
        val keyId = idHash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val proof = mac.doFinal("$action\n$deviceId\n$nonce".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "device_id=${URLEncoder.encode(deviceId, "UTF-8")}" +
            "&nonce=${URLEncoder.encode(nonce, "UTF-8")}" +
            "&key_id=$keyId&proof=$proof"
    }
}
