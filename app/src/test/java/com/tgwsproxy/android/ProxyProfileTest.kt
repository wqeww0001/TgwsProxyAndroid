package com.tgwsproxy.android

import com.tgwsproxy.android.config.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyProfileTest {
    private val secret = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun importsTgProxyLinkWithDdPrefix() {
        val cfg = ProxyProfile.parseImport("tg://proxy?server=127.0.0.1&port=1443&secret=dd$secret")
        assertNotNull(cfg)
        assertEquals(secret, cfg!!.secret)
    }

    @Test
    fun rejectsInvalidSecret() {
        assertNull(ProxyProfile.parseImport("tg://proxy?secret=zz"))
    }

    @Test
    fun jsonRoundTripKeepsSettings() {
        val profile = ProxyProfile(
            id = "custom",
            ruName = "r",
            enName = "e",
            ruDesc = "",
            enDesc = "",
            cfWorkerDomain = "worker.example.com",
            cfEnabled = false,
            poolSize = 6,
            smartStandby = false,
            dcMappings = "2:149.154.167.51",
        )
        val cfg = ProxyProfile.parseImport(profile.exportToJson(secret))
        assertNotNull(cfg)
        assertEquals(secret, cfg!!.secret)
        assertEquals("worker.example.com", cfg.cfWorkerDomain)
        assertEquals(6, cfg.poolSize)
        assertFalse(cfg.cfEnabled)
        assertEquals("2:149.154.167.51", cfg.dcMappings)
    }

    @Test
    fun legacyWebProxyKeysAreIgnored() {
        val json = """{"v":2,"secret":"$secret","web_proxy_enabled":true,"web_proxy_server":"x.example.com"}"""
        val cfg = ProxyProfile.parseImport(json)
        assertNotNull(cfg)
        assertEquals(secret, cfg!!.secret)
    }

    @Test
    fun invalidJsonIsRejected() {
        assertNull(ProxyProfile.parseImport("{not json}"))
    }
}
