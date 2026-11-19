package com.wdtt.client.detector

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class ProbeResult(
    val url: String,
    val isSuccess: Boolean,
    val httpCode: Int = 0,
    val latencyMs: Long = 0L,
    val error: String? = null
)

data class DetectionReport(
    val state: WhiteListDetector.NetworkState,
    val externalProbes: List<ProbeResult>,
    val whitelistProbes: List<ProbeResult>,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Автономный детектор Белых Списков (БС) оператора для Moschata VPN.
 * Проверяет доступность независимых внешних сервисов против социально значимых ресурсов РФ.
 */
class WhiteListDetector(
    private val probeTimeoutMs: Long = 1800L
) {

    enum class NetworkState {
        /** Сеть чистая: внешний интернет доступен без ограничений БС */
        FULL_INTERNET,

        /** Активен Белый Список: внешний интернет заблокирован оператором, но сервисы из БС РФ доступны */
        WHITELIST_ONLY,

        /** Интернета нет совсем: ни внешние, ни российские сервисы не отвечают */
        NO_INTERNET
    }

    companion object {
        val DEFAULT_EXTERNAL_PROBES = listOf(
            "https://www.google.com/generate_204",
            "https://1.1.1.1",
            "https://gitlab.com/favicon.ico"
        )

        val DEFAULT_WHITELIST_PROBES = listOf(
            "https://vk.ru/favicon.ico",
            "https://yandex.ru/favicon.ico",
            "https://gosuslugi.ru/favicon.ico"
        )
    }

    private val client: OkHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .connectTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(probeTimeoutMs + 300L, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Быстрая классификация состояния сети за 150-2000 мс.
     */
    suspend fun detect(
        externalUrls: List<String> = DEFAULT_EXTERNAL_PROBES,
        whitelistUrls: List<String> = DEFAULT_WHITELIST_PROBES
    ): NetworkState = coroutineScope {
        val externalSuccessDeferred = CompletableDeferred<Boolean>()
        val whitelistSuccessDeferred = CompletableDeferred<Boolean>()

        var pendingExternal = externalUrls.size
        var pendingWhitelist = whitelistUrls.size

        externalUrls.forEach { url ->
            launch(Dispatchers.IO) {
                val ok = executeProbe(url).isSuccess
                if (ok) {
                    externalSuccessDeferred.complete(true)
                } else {
                    synchronized(externalSuccessDeferred) {
                        pendingExternal--
                        if (pendingExternal <= 0) {
                            externalSuccessDeferred.complete(false)
                        }
                    }
                }
            }
        }

        whitelistUrls.forEach { url ->
            launch(Dispatchers.IO) {
                val ok = executeProbe(url).isSuccess
                if (ok) {
                    whitelistSuccessDeferred.complete(true)
                } else {
                    synchronized(whitelistSuccessDeferred) {
                        pendingWhitelist--
                        if (pendingWhitelist <= 0) {
                            whitelistSuccessDeferred.complete(false)
                        }
                    }
                }
            }
        }

        val hasExternal = externalSuccessDeferred.await()
        if (hasExternal) {
            coroutineContext.cancelChildren()
            return@coroutineScope NetworkState.FULL_INTERNET
        }

        val hasWhitelist = whitelistSuccessDeferred.await()
        coroutineContext.cancelChildren()

        if (hasWhitelist) {
            NetworkState.WHITELIST_ONLY
        } else {
            NetworkState.NO_INTERNET
        }
    }

    /**
     * Детальный замер всех зондов с возвратом отчета.
     */
    suspend fun detectDetailed(
        externalUrls: List<String> = DEFAULT_EXTERNAL_PROBES,
        whitelistUrls: List<String> = DEFAULT_WHITELIST_PROBES
    ): DetectionReport = coroutineScope {
        val startTime = System.currentTimeMillis()

        val externalDeferred = externalUrls.map { url ->
            async(Dispatchers.IO) { executeProbe(url) }
        }
        val whitelistDeferred = whitelistUrls.map { url ->
            async(Dispatchers.IO) { executeProbe(url) }
        }

        val externalResults = externalDeferred.awaitAll()
        val whitelistResults = whitelistDeferred.awaitAll()

        val hasExternal = externalResults.any { it.isSuccess }
        val hasWhitelist = whitelistResults.any { it.isSuccess }

        val state = when {
            hasExternal -> NetworkState.FULL_INTERNET
            hasWhitelist -> NetworkState.WHITELIST_ONLY
            else -> NetworkState.NO_INTERNET
        }

        val duration = System.currentTimeMillis() - startTime
        DetectionReport(
            state = state,
            externalProbes = externalResults,
            whitelistProbes = whitelistResults,
            durationMs = duration
        )
    }

    private suspend fun executeProbe(url: String): ProbeResult = suspendCancellableCoroutine { continuation ->
        val startNs = System.nanoTime()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-store")
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0)")
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val latency = (System.nanoTime() - startNs) / 1_000_000L
                if (continuation.isActive) {
                    continuation.resume(
                        ProbeResult(url = url, isSuccess = false, httpCode = 0, latencyMs = latency, error = e.message),
                        onCancellation = null
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val latency = (System.nanoTime() - startNs) / 1_000_000L
                val code = response.code
                response.close()
                val isReachable = code in 100..499
                if (continuation.isActive) {
                    continuation.resume(
                        ProbeResult(url = url, isSuccess = isReachable, httpCode = code, latencyMs = latency),
                        onCancellation = null
                    )
                }
            }
        })
    }

    fun shutdown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}

/**
 * Глобальный синглтон-менеджер тестирования Белого Списка для Compose UI.
 */
object WhiteListDetectorManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detector = WhiteListDetector(probeTimeoutMs = 1800L)

    private val _report = MutableStateFlow<DetectionReport?>(null)
    val report = _report.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    fun runCheck() {
        if (_isChecking.value) return
        scope.launch {
            _isChecking.value = true
            try {
                val rep = detector.detectDetailed()
                _report.value = rep
            } catch (e: Exception) {
                _report.value = DetectionReport(
                    state = WhiteListDetector.NetworkState.NO_INTERNET,
                    externalProbes = emptyList(),
                    whitelistProbes = emptyList(),
                    durationMs = 0L
                )
            } finally {
                _isChecking.value = false
            }
        }
    }
}
