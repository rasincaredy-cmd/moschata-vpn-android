package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWorkersTest {

    @Test
    fun testWorkerGroupConstants() {
        assertEquals(9, SettingsStore.WORKERS_PER_VK_GROUP)
        assertEquals(6, SettingsStore.MAX_VK_HASHES)
        assertEquals(162, SettingsStore.MAX_TOTAL_WORKERS)
        assertEquals(4, SettingsStore.VK_ACCOUNT_MAX_WORKERS)
    }

    @Test
    fun testMaxAnonymousWorkersScaling() {
        // 1 hash: 1 * 9 * 3 = 27
        assertEquals(27, SettingsStore.maxAnonymousWorkers(1))
        // 2 hashes: 2 * 9 * 3 = 54
        assertEquals(54, SettingsStore.maxAnonymousWorkers(2))
        // 3 hashes: 3 * 9 * 3 = 81
        assertEquals(81, SettingsStore.maxAnonymousWorkers(3))
        // 6 hashes: 6 * 9 * 3 = 162
        assertEquals(162, SettingsStore.maxAnonymousWorkers(6))
        // Clamped at MAX_TOTAL_WORKERS
        assertEquals(162, SettingsStore.maxAnonymousWorkers(10))
        assertEquals(27, SettingsStore.maxAnonymousWorkers(0))
    }

    @Test
    fun testMultiHashParsing() {
        val singleHash = "vk_call_hash_abc123"
        val singleList = singleHash.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() }
        assertEquals(1, singleList.size)

        val doubleHash = "hash1,hash2"
        val doubleList = doubleHash.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() }
        assertEquals(2, doubleList.size)

        val multilineHashes = "hash1\nhash2,  hash3"
        val multiList = multilineHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() }
        assertEquals(3, multiList.size)
    }

    @Test
    fun testWorkerOptionsStepOfNine() {
        // For 2 hashes, should produce [9, 18, 27, 36, 45, 54]
        val hashCount = 2
        val options = (1..(hashCount * 3)).map { it * 9 }
        assertEquals(listOf(9, 18, 27, 36, 45, 54), options)
        assertTrue(options.all { it % 9 == 0 })
    }
}
