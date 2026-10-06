package com.tgwsproxy.android

import com.tgwsproxy.android.webproxy.WebProxyProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Local unit tests for ProxyConfig, UpdateChecker, and Telegram Web Proxy (tproxy-v1) protocol.
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
    fun versionComparison_isNumericAndStable() {
        assertEquals(true, UpdateChecker.isNewerForTest("2.1.0", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.3", "2.0.3"))
        assertEquals(false, UpdateChecker.isNewerForTest("2.0.2", "2.0.3"))
    }

    @Test
    fun webProxyBridgeCapability_matchesOfficialSpecVectors() {
        val raw16 = WebProxyProtocol.decodeSecretBytes("000102030405060708090a0b0c0d0e0f")
        assertNotNull(raw16)
        assertEquals(
            "doO-OToiNqxyID2JxoMc9lwHJ5lpNXQqVLbae-UBS4Y",
            WebProxyProtocol.deriveBridgeCapability("example.com", "", raw16!!),
        )
        assertEquals(
            "7Aln5tlmlmY5XwwaB5JTUJdo00pvvGUaAVnO4DT5U-k",
            WebProxyProtocol.deriveBridgeCapability("example.com", "portal", raw16),
        )

        val dd17 = WebProxyProtocol.decodeSecretBytes("dd000102030405060708090a0b0c0d0e0f")
        assertNotNull(dd17)
        assertEquals(17, dd17!!.size)
        // Marked secret roundtrip (0x70 prefix)
        val marked16 = WebProxyProtocol.encodeMarkedSecret(raw16)
        assertEquals("pAAECAwQFBgcICQoLDA0ODw", marked16)
        val marked17 = WebProxyProtocol.encodeMarkedSecret(dd17)
        assertEquals("p3QABAgMEBQYHCAkKCwwNDg8", marked17)
    }

    @Test
    fun webProxyLinks_parseRootAndBasePathCorrectly() {
        val rootLink = "https://t.me/webproxy?server=example.com&secret=000102030405060708090a0b0c0d0e0f"
        val rootEp = WebProxyProtocol.parseWebProxyLink(rootLink)
        assertNotNull(rootEp)
        assertEquals("example.com", rootEp!!.host)
        assertEquals("", rootEp.basePath)
        assertEquals("dd000102030405060708090a0b0c0d0e0f", rootEp.localTelegramSecretHex)

        val basePathLink = "https://t.me/webproxy?server=example.com%2Fportal&secret=pAAECAwQFBgcICQoLDA0ODw"
        val basePathEp = WebProxyProtocol.parseWebProxyLink(basePathLink)
        assertNotNull(basePathEp)
        assertEquals("example.com", basePathEp!!.host)
        assertEquals("portal", basePathEp.basePath)
        assertEquals("example.com/portal", basePathEp.serverParam)

        // Base-path link with unmarked secret must be rejected per BASE_PATH.md
        val invalidUnmarked = "https://t.me/webproxy?server=example.com%2Fportal&secret=000102030405060708090a0b0c0d0e0f"
        assertNull(WebProxyProtocol.parseWebProxyLink(invalidUnmarked))
    }

    @Test
    fun webProxyFrames_encodeAndDecodeBatch() {
        val hello = WebProxyProtocol.helloFrame()
        val open = WebProxyProtocol.openFrame(1)
        val data = WebProxyProtocol.dataFrame(1, byteArrayOf(10, 20, 30))
        val window = WebProxyProtocol.windowFrame(1, 65536)
        val close = WebProxyProtocol.closeFrame(1)

        val batch = hello + open + data + window + close
        val frames = WebProxyProtocol.decodeFrames(batch)
        assertEquals(5, frames.size)
        assertEquals(WebProxyProtocol.TYPE_HELLO, frames[0].type)
        assertEquals(0, frames[0].streamId)
        assertArrayEquals(byteArrayOf(0x01), frames[0].payload)

        assertEquals(WebProxyProtocol.TYPE_OPEN, frames[1].type)
        assertEquals(1, frames[1].streamId)

        assertEquals(WebProxyProtocol.TYPE_DATA, frames[2].type)
        assertEquals(1, frames[2].streamId)
        assertArrayEquals(byteArrayOf(10, 20, 30), frames[2].payload)

        assertEquals(WebProxyProtocol.TYPE_WINDOW, frames[3].type)
        assertEquals(65536L, WebProxyProtocol.parseWindowDelta(frames[3].payload))

        assertEquals(WebProxyProtocol.TYPE_CLOSE, frames[4].type)
        assertEquals(1, frames[4].streamId)
    }
}

