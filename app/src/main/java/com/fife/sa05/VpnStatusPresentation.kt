package com.fife.sa05

enum class VpnPrimaryAction {
    CONNECT,
    STOP,
    RETRY,
    OPEN_SUBSCRIPTION
}

enum class VpnSecondaryAction {
    NETWORK_SETTINGS,
    CHANGE_PROFILE,
    CHANGE_STRATEGY,
    DIAGNOSTICS
}

data class VpnStatusPresentation(
    val title: String,
    val description: String,
    val primaryAction: VpnPrimaryAction,
    val secondaryActions: List<VpnSecondaryAction>
)

fun vpnStatusPresentation(
    snapshot: VpnRuntimeSnapshot,
    bsTrafficExceeded: Boolean = false
): VpnStatusPresentation {
    val profileDescription = snapshot.profileName.ifBlank { snapshot.backend.title }
    return when (snapshot.status) {
        VpnRunStatus.DISCONNECTED -> VpnStatusPresentation(
            title = if (bsTrafficExceeded) {
                "Лимит трафика БС исчерпан"
            } else {
                "VPN выключен"
            },
            description = if (bsTrafficExceeded) {
                "Введите код на интернет или дождитесь сброса периода"
            } else {
                "Выберите сервер и подключите VPN"
            },
            primaryAction = VpnPrimaryAction.CONNECT,
            secondaryActions = emptyList()
        )
        VpnRunStatus.CONNECTING -> VpnStatusPresentation(
            title = "Подключаем VPN…",
            description = snapshot.message.ifBlank { profileDescription },
            primaryAction = VpnPrimaryAction.STOP,
            secondaryActions = emptyList()
        )
        VpnRunStatus.CONNECTED -> VpnStatusPresentation(
            title = "VPN включён",
            description = listOf(profileDescription, snapshot.message)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" · "),
            primaryAction = VpnPrimaryAction.STOP,
            secondaryActions = emptyList()
        )
        VpnRunStatus.RECOVERING -> VpnStatusPresentation(
            title = "Восстанавливаем VPN",
            description = snapshot.message.ifBlank { "Проверяем маршрут после смены сети" },
            primaryAction = VpnPrimaryAction.STOP,
            secondaryActions = emptyList()
        )
        VpnRunStatus.WAITING_FOR_NETWORK -> VpnStatusPresentation(
            title = "Нет подключения к сети",
            description = snapshot.message.ifBlank { "VPN продолжит работу, когда сеть появится" },
            primaryAction = VpnPrimaryAction.STOP,
            secondaryActions = listOf(VpnSecondaryAction.NETWORK_SETTINGS)
        )
        VpnRunStatus.ERROR -> VpnStatusPresentation(
            title = when (snapshot.failureKind) {
                VpnFailureKind.AUTHORIZATION -> "Нужна действующая подписка"
                VpnFailureKind.QUOTA -> "Лимит трафика БС исчерпан"
                else -> "Не удалось подключить VPN"
            },
            description = snapshot.message.ifBlank {
                when (snapshot.failureKind) {
                    VpnFailureKind.QUOTA ->
                        "Подключение недоступно до сброса периода"
                    else -> "Повторите попытку или откройте проверку"
                }
            },
            primaryAction = when (snapshot.failureKind) {
                VpnFailureKind.AUTHORIZATION -> VpnPrimaryAction.OPEN_SUBSCRIPTION
                VpnFailureKind.QUOTA -> VpnPrimaryAction.CONNECT
                else -> VpnPrimaryAction.RETRY
            },
            secondaryActions = when (snapshot.failureKind) {
                VpnFailureKind.NETWORK -> listOf(
                    VpnSecondaryAction.NETWORK_SETTINGS,
                    VpnSecondaryAction.DIAGNOSTICS
                )
                VpnFailureKind.AUTHORIZATION, VpnFailureKind.QUOTA -> emptyList()
                else -> listOf(
                    if (snapshot.backend.usesXrayProfile) {
                        VpnSecondaryAction.CHANGE_PROFILE
                    } else {
                        VpnSecondaryAction.CHANGE_STRATEGY
                    },
                    VpnSecondaryAction.DIAGNOSTICS
                )
            }
        )
    }
}
