package com.tgwsproxy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConfigTest {
    private val secret = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun linkUsesDefaultPortUntilChanged() {
        assertTrue(ProxyConfig.telegramProxyLink(secret).contains("port=${ProxyConfig.DEFAULT_PORT}&"))
    }

    @Test
    fun linkAddsDdPrefixOnlyOnce() {
        assertEquals(ProxyConfig.telegramProxyLink(secret), ProxyConfig.telegramProxyLink("dd$secret"))
    }

    @Test
    fun allSupportedLanguagesHaveStrings() {
        assertEquals(13, AppLanguage.entries.size)
        for (lang in AppLanguage.entries) {
            assertEquals(lang, AppLanguage.from(lang.code))
            val ui = strings(lang)
            assertTrue(ui.start.isNotBlank())
            assertTrue(ui.stop.isNotBlank())
            assertTrue(ui.dcTestTitle.isNotBlank())
        }
    }
}
