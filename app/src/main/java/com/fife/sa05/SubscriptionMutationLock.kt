package com.fife.sa05

import kotlinx.coroutines.sync.Mutex

/** Один mutex для refresh подписки и ручного выбора сервера. */
internal object SubscriptionMutationLock {
    val mutex = Mutex()
}
