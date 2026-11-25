package com.wdtt.client

import com.wdtt.client.detector.DetectionReport
import com.wdtt.client.detector.WhiteListDetector
import com.wdtt.client.detector.WhiteListDetectorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteListCatalogTest {

    @Test
    fun testCatalogEndpointsCount() {
        assertEquals(9, WhiteListDetector.DEFAULT_EXTERNAL_PROBES.size)
        assertEquals(18, WhiteListDetector.DEFAULT_WHITELIST_PROBES.size)
        // Total 27 verified test endpoints
        assertEquals(27, WhiteListDetector.DEFAULT_EXTERNAL_PROBES.size + WhiteListDetector.DEFAULT_WHITELIST_PROBES.size)
    }

    @Test
    fun testResolveProbeTitle_russianServices() {
        assertEquals("Госуслуги", WhiteListDetector.resolveProbeTitle("https://gosuslugi.ru/favicon.ico"))
        assertEquals("ВКонтакте", WhiteListDetector.resolveProbeTitle("https://vk.ru/favicon.ico"))
        assertEquals("Яндекс", WhiteListDetector.resolveProbeTitle("https://yandex.ru/favicon.ico"))
        assertEquals("Сбербанк", WhiteListDetector.resolveProbeTitle("https://sberbank.ru/favicon.ico"))
        assertEquals("Ozon", WhiteListDetector.resolveProbeTitle("https://ozon.ru/favicon.ico"))
        assertEquals("Wildberries", WhiteListDetector.resolveProbeTitle("https://wildberries.ru/favicon.ico"))
        assertEquals("Авито", WhiteListDetector.resolveProbeTitle("https://avito.ru/favicon.ico"))
        assertEquals("2ГИС", WhiteListDetector.resolveProbeTitle("https://2gis.ru/favicon.ico"))
        assertEquals("Rutube", WhiteListDetector.resolveProbeTitle("https://rutube.ru/favicon.ico"))
        assertEquals("РЖД", WhiteListDetector.resolveProbeTitle("https://rzd.ru/favicon.ico"))
    }

    @Test
    fun testResolveProbeTitle_externalServices() {
        assertEquals("Google", WhiteListDetector.resolveProbeTitle("https://www.google.com/generate_204"))
        assertEquals("Cloudflare 1.1.1.1", WhiteListDetector.resolveProbeTitle("https://1.1.1.1"))
        assertEquals("GitHub", WhiteListDetector.resolveProbeTitle("https://github.com/favicon.ico"))
        assertEquals("Telegram", WhiteListDetector.resolveProbeTitle("https://telegram.org/favicon.ico"))
        assertEquals("Wikipedia", WhiteListDetector.resolveProbeTitle("https://en.wikipedia.org/favicon.ico"))
    }

    @Test
    fun testAllProbesHaveDistinctUrlsAndValidSchemes() {
        val allProbes = WhiteListDetector.DEFAULT_EXTERNAL_PROBES + WhiteListDetector.DEFAULT_WHITELIST_PROBES
        assertEquals(allProbes.size, allProbes.toSet().size)
        assertTrue(allProbes.all { it.startsWith("https://") })
    }

    @Test
    fun testBypassGate_blocksWhenFreeInternetWithoutVpn() {
        val freeOperatorReport = DetectionReport(
            state = WhiteListDetector.NetworkState.FULL_INTERNET,
            externalProbes = emptyList(),
            whitelistProbes = emptyList(),
            durationMs = 150L,
            viaVpn = false
        )
        assertTrue(WhiteListDetectorManager.isBypassBlockedByFreeInternet(freeOperatorReport))
        val gate = WhiteListDetectorManager.evaluateGate(freeOperatorReport)
        assertFalse(gate.allowed)
    }

    @Test
    fun testBypassGate_allowsWhenWhitelistActiveOrVpnReport() {
        val whitelistReport = DetectionReport(
            state = WhiteListDetector.NetworkState.WHITELIST_ONLY,
            externalProbes = emptyList(),
            whitelistProbes = emptyList(),
            durationMs = 1500L,
            viaVpn = false
        )
        assertFalse(WhiteListDetectorManager.isBypassBlockedByFreeInternet(whitelistReport))
        assertTrue(WhiteListDetectorManager.evaluateGate(whitelistReport).allowed)

        val viaVpnReport = DetectionReport(
            state = WhiteListDetector.NetworkState.FULL_INTERNET,
            externalProbes = emptyList(),
            whitelistProbes = emptyList(),
            durationMs = 200L,
            viaVpn = true
        )
        assertFalse(WhiteListDetectorManager.isBypassBlockedByFreeInternet(viaVpnReport))
        assertTrue(WhiteListDetectorManager.evaluateGate(viaVpnReport).allowed)
    }
}
