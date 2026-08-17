package com.fife.sa05

internal enum class NetworkRecoveryDecision {
    NONE,
    WAIT_FOR_NETWORK,
    VERIFY_ROUTE,
    RECONNECT,
    FAIL
}

internal fun vpnNetworkKey(
    networkHandle: Long,
    transport: String,
    interfaceName: String
): String = "$networkHandle|$transport|$interfaceName"

internal object NetworkRecoveryPolicy {
    const val MAX_AUTOMATIC_ATTEMPTS = 2

    fun networkChanged(previousKey: String?, currentKey: String?): NetworkRecoveryDecision = when {
        currentKey == null -> NetworkRecoveryDecision.WAIT_FOR_NETWORK
        previousKey == null || previousKey == currentKey -> NetworkRecoveryDecision.NONE
        else -> NetworkRecoveryDecision.VERIFY_ROUTE
    }

    fun routeChecked(
        healthy: Boolean,
        automaticAttempts: Int
    ): NetworkRecoveryDecision = when {
        healthy -> NetworkRecoveryDecision.NONE
        automaticAttempts < MAX_AUTOMATIC_ATTEMPTS -> NetworkRecoveryDecision.RECONNECT
        else -> NetworkRecoveryDecision.FAIL
    }
}
