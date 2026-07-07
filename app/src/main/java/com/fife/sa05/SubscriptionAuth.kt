package com.fife.sa05

object SubscriptionAuth {
    fun isAuthorized(state: SubscriptionState): Boolean =
        state.url.isNotBlank() && state.profiles.isNotEmpty()
}
