package com.fife.sa05

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendControllerTest {
    @Test
    fun `backend starts only for an authorized subscription`() {
        var starts = 0

        assertEquals(false, startAuthorizedBackend(false) { starts++ })
        assertEquals(0, starts)
        assertEquals(true, startAuthorizedBackend(true) { starts++ })
        assertEquals(1, starts)
    }
}
