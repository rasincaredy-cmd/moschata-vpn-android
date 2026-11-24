package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImportTest {

    @Test
    fun testProductionFeedWithServersAndProfilesCombined() {
        val json = """
            {
                "subscriptionName": "Moschata VPN Влад",
                "servers": [
                    {
                        "name": "Нидерланды (Телефон)",
                        "config": "[Interface]\nPrivateKey = aaaa\nAddress = 10.8.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = bbbb\nEndpoint = 48.102.173.96:585\nAllowedIPs = 0.0.0.0/0\n"
                    }
                ],
                "profiles": [
                    {
                        "name": "⚡ Нидерланды Резервное подключение (Телефон)",
                        "peer": "48.102.173.96:56000",
                        "hashes": "vk_hash_test",
                        "password": "pass_phone",
                        "device": "phone"
                    },
                    {
                        "name": "⚡ Нидерланды Резервное подключение (Комп)",
                        "peer": "48.102.173.96:56000",
                        "hashes": "vk_hash_test",
                        "password": "pass_pc",
                        "device": "pc"
                    }
                ]
            }
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.profiles.size)

        // 1-й сервер: WireGuard обычный скоростной VPN
        val wgServer = parsed.profiles[0]
        assertTrue(wgServer.name.contains("Нидерланды"))
        assertTrue(wgServer.name.contains("WireGuard"))
        assertEquals("48.102.173.96:585", wgServer.peer)
        assertEquals(ConnectionProfile.PROTOCOL_WIREGUARD, wgServer.protocolType)
        assertTrue(wgServer.isWireGuard)
        assertFalse(wgServer.isYandexDocs)
        assertTrue(wgServer.wireguardConfig.contains("Endpoint = 48.102.173.96:585"))

        // 2-й сервер: Обход БС (Телефон)
        val bypassPhone = parsed.profiles[1]
        assertTrue(bypassPhone.name.contains("Телефон"))
        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, bypassPhone.protocolType)
        assertEquals(ConnectionProfile.DEVICE_PHONE, bypassPhone.deviceType)
        assertEquals("48.102.173.96:56000", bypassPhone.peer)

        // 3-й сервер: Обход БС (Комп)
        val bypassPc = parsed.profiles[2]
        assertTrue(bypassPc.name.contains("Комп"))
        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, bypassPc.protocolType)
        assertEquals(ConnectionProfile.DEVICE_PC, bypassPc.deviceType)
    }

    @Test
    fun testStandardProfilesParsing_mainVpnUnchanged() {
        val json = """
            {
                "subscriptionName": "Moschata VPN Test",
                "profiles": [
                    {
                        "name": "Нидерланды WireGuard",
                        "peer": "48.102.173.96:585",
                        "password": "secret_wg_pass",
                        "workers": 9,
                        "port": 9000
                    },
                    {
                        "name": "Нидерланды Обход БС",
                        "peer": "48.102.173.96:56000",
                        "hashes": "vk_hash_1,vk_hash_2",
                        "password": "secret_bypass_pass",
                        "workers": 18,
                        "port": 9000,
                        "dk": "diag_test_key"
                    }
                ]
            }
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull("Payload must be parsed successfully", parsed)
        assertEquals("Moschata VPN Test", parsed!!.subscriptionName)
        assertEquals(2, parsed.profiles.size)

        val wg = parsed.profiles[0]
        assertEquals("Нидерланды WireGuard", wg.name)
        assertEquals("48.102.173.96:585", wg.peer)
        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, wg.protocolType)
        assertFalse("Standard WG profile must NOT be marked as YandexDocs", wg.isYandexDocs)
        assertTrue("Empty hashes profile should use global hashes", wg.useGlobalHashes)

        val bypass = parsed.profiles[1]
        assertEquals("Нидерланды Обход БС", bypass.name)
        assertEquals("48.102.173.96:56000", bypass.peer)
        assertEquals("vk_hash_1,vk_hash_2", bypass.vkHashes)
        assertEquals(18, bypass.workersPerHash)
        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, bypass.protocolType)
        assertFalse("qWDTT bypass profile must NOT be marked as YandexDocs", bypass.isYandexDocs)
        assertFalse("Profile with specific hashes must not use global hashes", bypass.useGlobalHashes)
        assertEquals("diag_test_key", bypass.diagKey)
    }

    @Test
    fun testYandexDocsProfileParsing_notDropped() {
        val json = """
            {
                "subscriptionName": "Moschata Premium",
                "profiles": [
                    {
                        "name": "Нидерланды Резервное подключение Яндекс",
                        "type": "yandex_docs",
                        "transport": "wss",
                        "peer": "docs.yandex.ru:443",
                        "password": "auth_token_yandex_123",
                        "server": "48.102.173.96",
                        "uri": "openflux://auth_token_yandex_123@docs.yandex.ru:443/doc/doc_uuid_456?server=48.102.173.96"
                    }
                ]
            }
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull("YandexDocs payload must not be dropped", parsed)
        assertEquals(1, parsed!!.profiles.size)

        val profile = parsed.profiles[0]
        assertTrue("Name should retain or have document indicator", profile.name.contains("Яндекс"))
        assertEquals("docs.yandex.ru:443", profile.peer)
        assertEquals(ConnectionProfile.PROTOCOL_YANDEX_DOCS, profile.protocolType)
        assertTrue("Must be marked as isYandexDocs", profile.isYandexDocs)
        assertFalse("YandexDocs must not use VK global hashes", profile.useGlobalHashes)
        assertEquals("auth_token_yandex_123", profile.password)
        assertEquals("48.102.173.96", profile.diagKey)
    }

    @Test
    fun testOpenFluxUriParsing_directLink() {
        val uri = "openflux://super_secret_token@docs.yandex.ru:443/doc/room999?server=13.143.175.31&name=Резерв"
        val parsed = SubscriptionImport.parsePayload(uri)
        assertNotNull("Direct openflux URI must be parsed", parsed)
        assertEquals(1, parsed!!.profiles.size)

        val profile = parsed.profiles[0]
        assertEquals(ConnectionProfile.PROTOCOL_YANDEX_DOCS, profile.protocolType)
        assertTrue(profile.isYandexDocs)
        assertEquals("docs.yandex.ru:443", profile.peer)
        assertEquals("super_secret_token", profile.password)
        assertEquals("13.143.175.31", profile.diagKey)
        assertTrue(profile.name.contains("Резерв"))
    }

    @Test
    fun testMixedSubscription_preservesIntegrity() {
        val json = """
            [
                {
                    "groupName": "Moschata Multi",
                    "name": "Основной WireGuard",
                    "peer": "48.102.173.96:585",
                    "password": "pass1"
                },
                {
                    "name": "Обход VK TURN",
                    "peer": "48.102.173.96:56000",
                    "hashes": "hash1",
                    "password": "pass2"
                },
                {
                    "name": "Резерв Яндекс",
                    "type": "yandex_docs",
                    "peer": "docs.yandex.ru:443",
                    "password": "pass3",
                    "server": "48.102.173.96"
                }
            ]
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.profiles.size)

        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, parsed.profiles[0].protocolType)
        assertFalse(parsed.profiles[0].isYandexDocs)

        assertEquals(ConnectionProfile.PROTOCOL_QWDTT, parsed.profiles[1].protocolType)
        assertFalse(parsed.profiles[1].isYandexDocs)

        assertEquals(ConnectionProfile.PROTOCOL_YANDEX_DOCS, parsed.profiles[2].protocolType)
        assertTrue(parsed.profiles[2].isYandexDocs)
    }

    @Test
    fun testDeviceTypeDetection_phoneVsPc() {
        val json = """
            {
                "subscriptionName": "Moschata Sub",
                "profiles": [
                    {
                        "name": "⚡ Нидерланды Резервное подключение (Телефон)",
                        "peer": "48.102.173.96:56000",
                        "password": "pass_phone",
                        "device": "phone"
                    },
                    {
                        "name": "⚡ Нидерланды Резервное подключение (Комп)",
                        "peer": "48.102.173.96:56000",
                        "password": "pass_pc",
                        "device": "pc"
                    },
                    {
                        "name": "Домашний Компьютер Windows",
                        "peer": "48.102.173.96:56000",
                        "password": "pass_win"
                    }
                ]
            }
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.profiles.size)

        val phoneProfile = parsed.profiles[0]
        assertEquals(ConnectionProfile.DEVICE_PHONE, phoneProfile.deviceType)
        assertFalse(phoneProfile.isPcDevice)

        val pcProfile = parsed.profiles[1]
        assertEquals(ConnectionProfile.DEVICE_PC, pcProfile.deviceType)
        assertTrue(pcProfile.isPcDevice)

        val nameDetectedPcProfile = parsed.profiles[2]
        assertEquals(ConnectionProfile.DEVICE_PC, nameDetectedPcProfile.deviceType)
        assertTrue(nameDetectedPcProfile.isPcDevice)
    }

    @Test
    fun testPortNormalization_rewrites56003ToDtls56000() {
        val json = """
            {
                "profiles": [
                    {
                        "name": "Резерв Raw Port Feed",
                        "peer": "48.102.173.96:56003",
                        "password": "pass"
                    }
                ]
            }
        """.trimIndent()

        val parsed = SubscriptionImport.parsePayload(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.profiles.size)
        assertEquals("48.102.173.96:56000", parsed.profiles[0].peer)
    }
}
