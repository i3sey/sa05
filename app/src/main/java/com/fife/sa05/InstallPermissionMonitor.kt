package com.fife.sa05

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class InstallPermissionMonitor(
    private val readPermission: () -> Boolean
) : DefaultLifecycleObserver {
    private val _canInstall = MutableStateFlow(readPermission())
    val canInstall: StateFlow<Boolean> = _canInstall.asStateFlow()

    override fun onResume(owner: LifecycleOwner) {
        _canInstall.value = readPermission()
    }
}
