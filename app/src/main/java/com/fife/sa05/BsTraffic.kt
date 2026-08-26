package com.fife.sa05

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * Лимит и учёт трафика режима «БС-туннель» — только на клиенте.
 *
 * Базовый месячный лимит задаётся в приложении. Дополнительный объём
 * добавляется одноразовыми кодами «на интернет» ([INTERNET_CODE_HASHES]):
 * в APK хранятся только SHA-256 хеши, не сам текст кода.
 *
 * Использованный объём считается локально (rx+tx по UID приложения).
 * Действует только для [VpnBackend.YCTUN].
 */
object BsTraffic {
    const val DEFAULT_LIMIT_BYTES = 1024L * 1024L * 1024L

    /** SHA-256(normalized code) → дополнительные байты лимита. */
    internal val INTERNET_CODE_HASHES: Map<String, Long> = mapOf(
        "ab29ab58ff18b4563c498e4f650954457941f78c37c2a37c2d4711b2002c63eb" to 536_870_912L,
        "790353b6cfea6704fd37fcbeca3adaf9d5673ec211d908924708a739149711f8" to 1_073_741_824L,
        "0aca227a892eab1ac74e1b90b64a2f26b112cef265f201794ce0c14c21dba84a" to 1_073_741_824L,
        "72260f613d5b05f597d6e61df2f7a73e866b734675f924c9dd2e0ef241050588" to 2_147_483_648L,
        "447261cd2f596dbb995a3235db068b481fb36186eae4c2ef421c168ab7d867ca" to 3_221_225_472L,
        "4c8adb1c0745f3298fd5387e60468da3c9f5e5d094f3017182d2f4bdf61fa5d4" to 5_368_709_120L,
        "0cdcfc56546129cf0ee110927d0bb38c3648ce8f378bd30c85615fd8514f2056" to 7_516_192_768L,
        "a6821696c098fe7fbec9ecc01b04eb2e38de76515e4b9d7a61ea3a8e71056963" to 10_737_418_240L,
        "e6a1181ac1ad1439612408744006e124049ffecc6b7c4ce1187dea68bfa4c762" to 2_147_483_648L,
        "a56f04697c3c205100051da988533dd184dbae25c358a4c76fb67f7c84fed71e" to 5_368_709_120L
    )

    enum class ApplyCodeResult {
        APPLIED,
        INVALID,
        ALREADY_USED
    }

    data class Policy(
        val limitBytes: Long,
        val expireAtSeconds: Long,
        val bonusBytes: Long = 0L
    )

    data class Snapshot(
        val session: TrafficUsage,
        val periodUsedBytes: Long,
        val limitBytes: Long,
        val expireAtSeconds: Long,
        val bonusBytes: Long = 0L
    ) {
        val isExceeded: Boolean
            get() = periodUsedBytes >= limitBytes

        val usageRatio: Float
            get() = if (limitBytes <= 0) 0f else
                (periodUsedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)

        val usagePercent: Int
            get() = (usageRatio * 100).toInt().coerceIn(0, 100)

        val isWarning: Boolean
            get() = usagePercent >= 80 && !isExceeded

        val isCritical: Boolean
            get() = usagePercent >= 90 && !isExceeded
    }

    fun policy(context: Context): Policy {
        val bonus = XrayPreferences.bsTrafficBonusBytes(context)
        val expire = defaultMonthExpireSeconds()
        return Policy(
            limitBytes = DEFAULT_LIMIT_BYTES + bonus,
            expireAtSeconds = expire,
            bonusBytes = bonus
        )
    }

    fun snapshot(
        context: Context,
        sessionUsage: TrafficUsage?
    ): Snapshot {
        val policy = policy(context)
        val used = reconcileUsedBytes(context, policy)
        return Snapshot(
            session = sessionUsage ?: TrafficUsage.EMPTY,
            periodUsedBytes = used,
            limitBytes = policy.limitBytes,
            expireAtSeconds = policy.expireAtSeconds,
            bonusBytes = policy.bonusBytes
        )
    }

    fun isExceeded(context: Context): Boolean {
        val policy = policy(context)
        return reconcileUsedBytes(context, policy) >= policy.limitBytes
    }

    suspend fun reconcile(context: Context) {
        reconcileUsedBytes(context, policy(context))
    }

    suspend fun applyInternetCode(context: Context, rawCode: String): ApplyCodeResult {
        val normalized = normalizeCode(rawCode)
        if (normalized.length < 16) return ApplyCodeResult.INVALID
        val hash = hashCode(normalized)
        val bonus = INTERNET_CODE_HASHES[hash] ?: return ApplyCodeResult.INVALID
        val applied = XrayPreferences.bsTrafficAppliedCodeHashes(context)
        if (hash in applied) return ApplyCodeResult.ALREADY_USED
        XrayPreferences.applyBsInternetCode(context, hash, bonus)
        reconcile(context)
        return ApplyCodeResult.APPLIED
    }

    suspend fun addBytes(context: Context, delta: Long) {
        if (delta <= 0) return
        val policy = policy(context)
        val current = reconcileUsedBytes(context, policy)
        XrayPreferences.setBsTrafficUsed(context, current + delta, policy.expireAtSeconds)
    }

    internal fun normalizeCode(raw: String): String =
        raw.trim().uppercase().filter { it.isLetterOrDigit() }

    internal fun hashCode(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun reconcileUsedBytes(context: Context, policy: Policy): Long {
        val stored = XrayPreferences.bsTrafficLedger(context)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val periodExpired = stored.periodExpireAt > 0 && nowSeconds > stored.periodExpireAt
        val periodChanged = stored.periodExpireAt != policy.expireAtSeconds
        if (periodExpired || periodChanged) {
            runBlocking {
                XrayPreferences.setBsTrafficUsed(context, 0L, policy.expireAtSeconds)
            }
            return 0L
        }
        return stored.usedBytes.coerceAtLeast(0L)
    }

    internal fun defaultMonthExpireSeconds(): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(
            Calendar.DAY_OF_MONTH,
            calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        )
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis / 1000L
    }
}
