package com.tgwsproxy.android

import com.tgwsproxy.android.util.QrGenerator
import org.junit.Assert.*
import org.junit.Test

/**
 * Local unit tests for ProxyConfig, UpdateChecker, and QrGenerator.
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
    fun dcMappings_validateAndConvertMultilineInput() {
        val mappings = " 2:149.154.167.51\n4:149.154.167.91 "
        assertTrue(ProxyConfig.isValidDcMappings(mappings))
        assertEquals("2:149.154.167.51,4:149.154.167.91", ProxyConfig.dcMappingsForNative(mappings))
        assertFalse(ProxyConfig.isValidDcMappings("2:999.1.1.1"))
        assertFalse(ProxyConfig.isValidDcMappings("6:149.154.167.51"))
        assertFalse(ProxyConfig.isValidDcMappings("telegram.example.com"))
    }

    @Test
    fun versionComparison_isNumericAndHandlesPreReleaseCorrectly() {
        assertEquals(true, UpdateChecker.isNewerForTest("2.1.0", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.3", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.2", "2.0.3"))
        // Stable release with same numeric core is newer than pre-release
        assertEquals(true, UpdateChecker.isNewerForTest("2.6.0", "2.6.0-beta.1"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.6.0-beta.1", "2.6.0"))
        assertEquals(true, UpdateChecker.isNewerForTest("2.6.0-beta.2", "2.6.0-beta.1"))
        assertEquals(true, UpdateChecker.isOlder("2.5.1", "2.5.2"))
        assertEquals(false, UpdateChecker.isOlder("2.5.2", "2.5.2"))
    }

    @Test
    fun qrGenerator_encodesShortAndLongProxyLinksUpToVersion10() {
        val shortLink = "tg://proxy?server=127.0.0.1&port=1443&secret=dd000102030405060708090a0b0c0d0e0f"
        val mShort = QrGenerator.encode(shortLink)
        // 81 bytes -> Version 5 (37x37)
        assertEquals(37, mShort.size)

        val longLink = "tg://proxy?server=127.0.0.1&port=1443&secret=dd000102030405060708090a0b0c0d0e0f&extra=" + "a".repeat(120)
        val mLong = QrGenerator.encode(longLink)
        // >154 bytes -> Version 8 (49x49)
        assertTrue(mLong.size >= 45)
    }
}

