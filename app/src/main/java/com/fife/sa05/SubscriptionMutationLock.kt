package com.fife.sa05

import kotlinx.coroutines.sync.Mutex

/**
 * Замок только на запись подписки. Скачивание его не держит, чтобы выбор
 * сервера не ждал сеть.
 */
internal object SubscriptionMutationLock {
    val mutex = Mutex()
}
