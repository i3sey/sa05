package com.fife.sa05

import com.fife.sa05.components.VpnHeroState

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

/**
 * How the hero control should present a runtime status.
 *
 * `WAITING_FOR_NETWORK` counts as busy rather than failed: the VPN is still up and will carry
 * on by itself once the network returns, so presenting it as a failure would invite the user to
 * go and fix something that is not broken.
 */
internal fun vpnHeroState(status: VpnRunStatus): VpnHeroState = when (status) {
    VpnRunStatus.DISCONNECTED -> VpnHeroState.OFF
    VpnRunStatus.CONNECTED -> VpnHeroState.ON
    VpnRunStatus.CONNECTING,
    VpnRunStatus.RECOVERING,
    VpnRunStatus.WAITING_FOR_NETWORK -> VpnHeroState.BUSY
    VpnRunStatus.ERROR -> VpnHeroState.FAILED
}

/**
 * Plain-language note for a component that is degraded or dead, so a broken piece of the stack
 * is visible without making the user read "tun2socks". Returns null while everything is fine —
 * healthy components are not worth screen space.
 */
internal fun componentTrouble(components: List<VpnComponentSnapshot>): String? {
    val byComponent = components.associateBy { it.component }
    fun state(component: VpnRuntimeComponent) = byComponent[component]?.state

    if (state(VpnRuntimeComponent.BYEDPI) == VpnComponentState.FALLBACK) {
        return "Локальный обход не взлетел: YouTube идёт через выбранный сервер"
    }
    val failed = components.filter { it.state == VpnComponentState.FAILED }
    if (failed.isEmpty()) return null
    return when {
        failed.any { it.component == VpnRuntimeComponent.TUN } ->
            "Туннель закрылся, перезапускаем VPN"
        failed.any { it.component == VpnRuntimeComponent.TUN2SOCKS } ->
            "Перенос трафика в туннель остановился, восстанавливаем"
        failed.any { it.component == VpnRuntimeComponent.XRAY } ->
            "Соединение с сервером оборвалось, восстанавливаем"
        failed.any { it.component == VpnRuntimeComponent.BYEDPI } ->
            "Локальный обход остановился, восстанавливаем"
        failed.any { it.component == VpnRuntimeComponent.TELEGRAM } ->
            "Telegram Proxy остановился"
        else -> "Один из компонентов VPN остановился"
    }
}

fun vpnStatusPresentation(snapshot: VpnRuntimeSnapshot): VpnStatusPresentation {
    val profileDescription = snapshot.profileName.ifBlank { snapshot.backend.title }
    return when (snapshot.status) {
        VpnRunStatus.DISCONNECTED -> VpnStatusPresentation(
            title = "VPN выключен",
            description = "Выберите сервер и подключите VPN",
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
            title = if (snapshot.failureKind == VpnFailureKind.AUTHORIZATION) {
                "Нужна действующая подписка"
            } else {
                "Не удалось подключить VPN"
            },
            description = snapshot.message.ifBlank { "Повторите попытку или откройте проверку" },
            primaryAction = if (snapshot.failureKind == VpnFailureKind.AUTHORIZATION) {
                VpnPrimaryAction.OPEN_SUBSCRIPTION
            } else {
                VpnPrimaryAction.RETRY
            },
            secondaryActions = when (snapshot.failureKind) {
                VpnFailureKind.NETWORK -> listOf(
                    VpnSecondaryAction.NETWORK_SETTINGS,
                    VpnSecondaryAction.DIAGNOSTICS
                )
                VpnFailureKind.AUTHORIZATION -> emptyList()
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
