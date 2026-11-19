package com.wdtt.client.detector

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Автономный детектор Белых Списков (БС) оператора для Moschata VPN.
 * Проверяет доступность независимых внешних сервисов против социально значимых ресурсов РФ.
 */
class WhiteListDetector(
    private val probeTimeoutMs: Long = 1500L
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
        /**
         * Внешние независимые хосты:
         * 1. Google 204 — стандартный captive-portal Android (быстрый, без тела ответа).
         * 2. Прямой IP 1.1.1.1 — исключает влияние блокировки или подмены DNS оператором.
         * 3. GitLab — независимый зарубежный репозиторий.
         */
        val DEFAULT_EXTERNAL_PROBES = listOf(
            "https://www.google.com/generate_204",
            "https://1.1.1.1",
            "https://gitlab.com/favicon.ico"
        )

        /**
         * Хосты, гарантированно включенные в Белый Список социально значимых ресурсов РФ:
         */
        val DEFAULT_WHITELIST_PROBES = listOf(
            "https://vk.ru/favicon.ico",
            "https://yandex.ru/favicon.ico",
            "https://gosuslugi.ru/favicon.ico"
        )
    }

    // Изолированный OkHttpClient с поддержкой сертификатов Минцифры и ранним выходом
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

        // 1. Параллельный запуск внешних зондов
        externalUrls.forEach { url ->
            launch(Dispatchers.IO) {
                val ok = executeProbe(url)
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

        // 2. Параллельный запуск контрольных зондов Белого Списка РФ
        whitelistUrls.forEach { url ->
            launch(Dispatchers.IO) {
                val ok = executeProbe(url)
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

        // Ранний выход: если хотя бы один внешний сайт ответил -> моментально FULL_INTERNET
        val hasExternal = externalSuccessDeferred.await()
        if (hasExternal) {
            coroutineContext.cancelChildren()
            return@coroutineScope NetworkState.FULL_INTERNET
        }

        // Если внешний интернет не ответил, проверяем Белый Список РФ
        val hasWhitelist = whitelistSuccessDeferred.await()
        coroutineContext.cancelChildren()

        if (hasWhitelist) {
            NetworkState.WHITELIST_ONLY
        } else {
            NetworkState.NO_INTERNET
        }
    }

    /**
     * Неблокирующий HTTP-запрос без скачивания тела.
     */
    private suspend fun executeProbe(url: String): Boolean = suspendCancellableCoroutine { continuation ->
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
                if (continuation.isActive) {
                    continuation.resume(false) {}
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // Коды 100..499 свидетельствуют о том, что веб-сервер ответил
                    val isReachable = it.code in 100..499
                    if (continuation.isActive) {
                        continuation.resume(isReachable) {}
                    }
                }
            }
        })
    }

    fun shutdown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}
