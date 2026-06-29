package com.fife.sa05

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPermissionMonitorTest {
    @Test
    fun refreshesPermissionWhenAppResumes() {
        var permissionGranted = false
        val monitor = InstallPermissionMonitor { permissionGranted }

        assertFalse(monitor.canInstall.value)

        permissionGranted = true
        monitor.onResume(TestLifecycleOwner)

        assertTrue(monitor.canInstall.value)
    }

    private object TestLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = error("Lifecycle is not read by InstallPermissionMonitor")
    }
}
