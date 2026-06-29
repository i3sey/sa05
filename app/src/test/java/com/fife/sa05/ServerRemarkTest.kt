package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerRemarkTest {
    @Test
    fun extractsFlagBeforeServerName() {
        assertEquals(
            ServerRemark(name = "Frankfurt", flag = "🇩🇪"),
            parseServerRemark("🇩🇪 Frankfurt")
        )
    }

    @Test
    fun extractsFlagAfterServerNameAndRemovesSeparator() {
        assertEquals(
            ServerRemark(name = "Amsterdam", flag = "🇳🇱"),
            parseServerRemark("Amsterdam | 🇳🇱")
        )
    }

    @Test
    fun keepsServerNameWithoutFlag() {
        val result = parseServerRemark("Primary server")

        assertEquals("Primary server", result.name)
        assertNull(result.flag)
    }
}
