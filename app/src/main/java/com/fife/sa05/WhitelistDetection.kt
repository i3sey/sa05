package com.fife.sa05

/**
 * Some Russian operators fall back to an allow-list: domestic services keep working while
 * everything else stops. It looks like a broken connection, so people reach for the VPN — and
 * the VPN cannot help either, because the server it dials is off the list too.
 *
 * A subscription usually carries one profile built to get out of that, and the whole point of
 * detecting the pattern is to offer that profile instead of leaving the user to guess.
 */
internal data class WhitelistVerdict(
    val suspected: Boolean,
    /** Domestic probes that answered — the evidence the network is up at all. */
    val workingDomestic: List<String>,
    /** Foreign probes that did not answer. */
    val blockedForeign: List<String>
)

/** Probes whose reachability says "the connection itself is alive". */
private val DOMESTIC_TARGET_IDS = setOf("yandex")

/**
 * Probes that stay reachable under ordinary Russian filtering, so their failure is what
 * separates an allow-list from routine throttling.
 *
 * Kinozal, NNMClub and RuTracker are deliberately absent: they are blocked in the normal case
 * too, so they say nothing about which case this is.
 */
private val FOREIGN_TARGET_IDS = setOf("google", "youtube", "telegram")

private const val MIN_FOREIGN_EVIDENCE = 2

/**
 * An allow-list is suspected when a domestic service answers while every foreign probe that ran
 * did not.
 *
 * Both halves matter. Without a working domestic probe this is indistinguishable from being
 * offline; without the foreign failures it is an ordinary working network. Requiring several
 * foreign probes keeps a single timeout from raising the alarm.
 */
internal fun detectWhitelist(results: List<DiagnosticResult>): WhitelistVerdict {
    val byId = results.associateBy { it.target.id }
    val domesticWorking = DOMESTIC_TARGET_IDS
        .mapNotNull { id -> byId[id]?.takeIf { it.reachable }?.target?.id }
    val foreignRan = FOREIGN_TARGET_IDS.mapNotNull { id -> byId[id] }
    val foreignBlocked = foreignRan.filterNot { it.reachable }.map { it.target.id }

    val suspected = domesticWorking.isNotEmpty() &&
        foreignRan.size >= MIN_FOREIGN_EVIDENCE &&
        foreignBlocked.size == foreignRan.size
    return WhitelistVerdict(
        suspected = suspected,
        workingDomestic = domesticWorking.sorted(),
        blockedForeign = foreignBlocked.sorted()
    )
}

/**
 * Finds the profile a provider ships for getting out from under an allow-list.
 *
 * Matched on word stems rather than a fixed name: the remark is free text written by the
 * provider, and the one in use here reads "Обход белых списоков" — misspelled, and still the
 * profile the user needs.
 */
internal fun findWhitelistBypassProfile(
    profiles: List<SubscriptionProfile>
): SubscriptionProfile? = profiles.firstOrNull { profile ->
    val remark = profile.remarks.lowercase()
    remark.contains("бел") && remark.contains("спис")
}
