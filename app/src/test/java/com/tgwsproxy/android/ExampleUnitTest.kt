package com.tgwsproxy.android

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun generatedSecret_isValidAndUnique() {
        val first = ProxyConfig.generateSecret()
        val second = ProxyConfig.generateSecret()
        assertEquals(true, ProxyConfig.isValidSecret(first))
        assertEquals(true, ProxyConfig.isValidSecret(second))
        assertEquals(false, first == second)
    }

    @Test
    fun domainNormalization_acceptsHostsAndRejectsInvalidInput() {
        assertEquals("worker.example.co.uk", ProxyConfig.normalizeDomain("https://Worker.Example.co.uk/path"))
        assertEquals("", ProxyConfig.normalizeDomain("localhost"))
        assertEquals("", ProxyConfig.normalizeDomain("-bad.example"))
    }

    @Test
    fun versionComparison_isNumericAndStable() {
        assertEquals(true, UpdateChecker.isNewerForTest("2.1.0", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.3", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.2", "2.0.3"))
    }
}
