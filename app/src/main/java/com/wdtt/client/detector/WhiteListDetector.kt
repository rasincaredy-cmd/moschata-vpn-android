package com.wdtt.client.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class ProbeResult(
    val url: String,
    val title: String,
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
    val timestamp: Long = System.currentTimeMillis(),
    val viaVpn: Boolean = false
)

/**
 * Автономный детектор Белых Списков (БС) оператора для Moschata VPN.
 * Проверяет доступность независимых внешних сервисов против социально значимых ресурсов РФ.
 */
class WhiteListDetector(
    private val probeTimeoutMs: Long = 2000L
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
            "https://github.com/favicon.ico",
            "https://gitlab.com/favicon.ico",
            "https://en.wikipedia.org/favicon.ico",
            "https://stackoverflow.com/favicon.ico",
            "https://python.org/favicon.ico",
            "https://www.mozilla.org/favicon.ico",
            "https://2ip.io/favicon.ico"
        )

        val DEFAULT_WHITELIST_PROBES = listOf(
            "https://gosuslugi.ru/favicon.ico",
            "https://vk.ru/favicon.ico",
            "https://yandex.ru/favicon.ico",
            "https://dzen.ru/favicon.ico",
            "https://ok.ru/favicon.ico",
            "https://mail.ru/favicon.ico",
            "https://ozon.ru/favicon.ico",
            "https://wildberries.ru/favicon.ico",
            "https://avito.ru/favicon.ico",
            "https://2gis.ru/favicon.ico",
            "https://rutube.ru/favicon.ico",
            "https://alfabank.ru/favicon.ico",
            "https://vtb.ru/favicon.ico",
            "https://tinkoff.ru/favicon.ico",
            "https://sberbank.ru/favicon.ico",
            "https://rzd.ru/favicon.ico",
            "https://cdek.ru/favicon.ico",
            "https://hh.ru/favicon.ico"
        )

        fun resolveProbeTitle(url: String): String {
            val host = url.removePrefix("https://").removePrefix("http://").substringBefore("/")
            return when {
                host.contains("gosuslugi") -> "Госуслуги"
                host.contains("vk.ru") || host.contains("vk.com") -> "ВКонтакте"
                host.contains("yandex") -> "Яндекс"
                host.contains("dzen") -> "Дзен"
                host.contains("ok.ru") -> "Одноклассники"
                host.contains("mail.ru") -> "Mail.ru"
                host.contains("ozon") -> "Ozon"
                host.contains("wildberries") -> "Wildberries"
                host.contains("avito") -> "Авито"
                host.contains("2gis") -> "2ГИС"
                host.contains("rutube") -> "Rutube"
                host.contains("alfabank") -> "Альфа-Банк"
                host.contains("vtb") -> "ВТБ"
                host.contains("tinkoff") -> "Т-Банк"
                host.contains("sberbank") -> "Сбербанк"
                host.contains("rzd") -> "РЖД"
                host.contains("cdek") -> "СДЭК"
                host.contains("hh.ru") -> "HeadHunter"
                host.contains("google") -> "Google"
                host == "1.1.1.1" -> "Cloudflare 1.1.1.1"
                host.contains("github") -> "GitHub"
                host.contains("gitlab") -> "GitLab"
                host.contains("wikipedia") -> "Wikipedia"
                host.contains("stackoverflow") -> "Stack Overflow"
                host.contains("python") -> "Python.org"
                host.contains("mozilla") -> "Mozilla"
                host.contains("2ip") -> "2IP"
                else -> host
            }
        }
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
                        ProbeResult(url = url, title = resolveProbeTitle(url), isSuccess = false, httpCode = 0, latencyMs = latency, error = e.message),
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
                        ProbeResult(url = url, title = resolveProbeTitle(url), isSuccess = isReachable, httpCode = code, latencyMs = latency),
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

data class BypassGateResult(
    val allowed: Boolean,
    val state: WhiteListDetector.NetworkState? = null,
    val message: String? = null
)

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

    @Volatile
    private var networkCallbackRegistered = false

    @Volatile
    private var lastObservedNetworkHandle: Long? = null

    fun ensureNetworkTracked(context: Context) {
        if (networkCallbackRegistered) return
        synchronized(this) {
            if (networkCallbackRegistered) return
            val cm = runCatching {
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull() ?: return
            lastObservedNetworkHandle = runCatching { cm.activeNetwork?.networkHandle }.getOrNull()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    val changed = lastObservedNetworkHandle != null && lastObservedNetworkHandle != handle
                    lastObservedNetworkHandle = handle
                    // Не сбрасываем статус оператора, если смена default network вызвана самим VPN-туннелем.
                    if (changed && !TunnelManager.running.value && !TunnelManager.isConnecting.value) {
                        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            _report.value = null
                        }
                    }
                }

                override fun onLost(network: Network) {
                    if (!TunnelManager.running.value && !TunnelManager.isConnecting.value) {
                        if (lastObservedNetworkHandle == network.networkHandle) {
                            lastObservedNetworkHandle = null
                            _report.value = null
                        }
                    }
                }
            }
            if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess) {
                networkCallbackRegistered = true
            }
        }
    }

    fun isBypassBlockedByFreeInternet(
        currentReport: DetectionReport? = _report.value,
        devAllowBypassOnFreeNet: Boolean = false
    ): Boolean {
        if (devAllowBypassOnFreeNet) return false
        return currentReport != null &&
            !currentReport.viaVpn &&
            currentReport.state == WhiteListDetector.NetworkState.FULL_INTERNET
    }

    fun invalidateIfStaleForSuspectedBypass() {
        val current = _report.value
        if (current != null && !current.viaVpn && current.state == WhiteListDetector.NetworkState.FULL_INTERNET) {
            _report.value = null
        }
    }

    fun runCheckIfUnchecked(context: Context) {
        ensureNetworkTracked(context)
        if (_report.value == null && !_isChecking.value && !TunnelManager.running.value && !TunnelManager.isConnecting.value) {
            runCheck(viaVpn = false)
        }
    }

    fun runCheck(viaVpn: Boolean = TunnelManager.running.value) {
        if (_isChecking.value) return
        scope.launch {
            executeDetailedCheck(viaVpn)
        }
    }

    /**
     * Проверяет перед запуском «Обхода БС», разрешён ли он на текущей сети.
     * Если свежий не-VPN тест уже показал свободный интернет — блокирует обход.
     * Если теста ещё не было — выполняет быстрый замер текущей сети.
     */
    suspend fun verifyCanStartBypass(context: Context): BypassGateResult {
        ensureNetworkTracked(context)
        val store = SettingsStore(context.applicationContext)
        val devOverride = runCatching {
            store.developerModeEnabled.first() && store.devAllowBypassOnFreeNet.first()
        }.getOrDefault(false)
        if (devOverride) {
            return BypassGateResult(allowed = true, state = _report.value?.state)
        }
        val existing = _report.value
        if (existing != null && !existing.viaVpn) {
            return evaluateGate(existing, devAllowBypassOnFreeNet = false)
        }
        val fresh = executeDetailedCheck(viaVpn = false)
        return evaluateGate(fresh, devAllowBypassOnFreeNet = false)
    }

    suspend fun canStartSavedTunnel(context: Context): BypassGateResult {
        val store = SettingsStore(context.applicationContext)
        val activeProto = store.activeProtocolType.first()
        val wgConfig = store.wireguardConfig.first()
        if (activeProto == ConnectionProfile.PROTOCOL_WIREGUARD && wgConfig.isNotBlank()) {
            return BypassGateResult(allowed = true)
        }
        if (activeProto == ConnectionProfile.PROTOCOL_YANDEX_DOCS) {
            return BypassGateResult(
                allowed = false,
                message = "Режим Яндекс.Документы (OpenFlux) в разработке. Выберите Обычный VPN или Обход БС."
            )
        }
        return verifyCanStartBypass(context)
    }

    internal fun evaluateGate(
        report: DetectionReport,
        devAllowBypassOnFreeNet: Boolean = false
    ): BypassGateResult {
        if (isBypassBlockedByFreeInternet(report, devAllowBypassOnFreeNet)) {
            return BypassGateResult(
                allowed = false,
                state = report.state,
                message = "Интернет свободен — «Обход БС» отключён, чтобы не тратить лимиты звонков VK. Используйте Обычный VPN."
            )
        }
        return BypassGateResult(allowed = true, state = report.state)
    }

    private suspend fun executeDetailedCheck(viaVpn: Boolean): DetectionReport {
        _isChecking.value = true
        return try {
            val rep = detector.detectDetailed().copy(viaVpn = viaVpn)
            _report.value = rep
            rep
        } catch (_: Exception) {
            val fallback = DetectionReport(
                state = WhiteListDetector.NetworkState.NO_INTERNET,
                externalProbes = emptyList(),
                whitelistProbes = emptyList(),
                durationMs = 0L,
                viaVpn = viaVpn
            )
            _report.value = fallback
            fallback
        } finally {
            _isChecking.value = false
        }
    }
}
