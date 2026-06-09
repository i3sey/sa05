package com.fife.sa05

import android.content.Context

object SubscriptionAuth {
    fun isAuthorized(state: SubscriptionState): Boolean =
        state.url.isNotBlank() && state.profiles.isNotEmpty()

    fun isAuthorized(context: Context): Boolean =
        isAuthorized(XrayPreferences.subscription(context))
}
