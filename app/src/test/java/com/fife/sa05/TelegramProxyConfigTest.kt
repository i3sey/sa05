package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProxyConfigTest {
    @Test
    fun generatedSecretIsValidAndRandom() {
        val first = TelegramProxyConfig.generateSecret()
        val second = TelegramProxyConfig.generateSecret()

        assertTrue(TelegramProxyConfig.isValidSecret(first))
        assertTrue(TelegramProxyConfig.isValidSecret(second))
        assertNotEquals(first, second)
    }

    @Test
    fun rejectsMalformedSecrets() {
        assertFalse(TelegramProxyConfig.isValidSecret(""))
        assertFalse(TelegramProxyConfig.isValidSecret("z".repeat(32)))
        assertFalse(TelegramProxyConfig.isValidSecret("a".repeat(31)))
    }

    @Test
    fun buildsNativeAndWebProxyLinks() {
        val secret = "0123456789abcdef0123456789abcdef"

        assertEquals(
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd$secret",
            TelegramProxyConfig.proxyUri(secret)
        )
        assertEquals(
            "https://t.me/proxy?server=127.0.0.1&port=1443&secret=dd$secret",
            TelegramProxyConfig.proxyUri(secret, webFallback = true)
        )
    }
}
