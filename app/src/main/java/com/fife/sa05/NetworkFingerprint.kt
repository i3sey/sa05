package com.fife.sa05

import org.json.JSONArray
import org.json.JSONObject

/**
 * A stable identity for "the kind of network I am on", used to remember which bypass strategy
 * worked there.
 *
 * The previous cache key included `Network.networkHandle`, which Android hands out afresh on
 * every reconnect, so a remembered preset was thrown away as soon as the user walked out of
 * Wi-Fi range and back. A fingerprint deliberately carries nothing per-connection: the same SIM
 * on the same operator produces the same key tomorrow and on a different cell.
 *
 * Nothing here needs a runtime permission. `TelephonyManager.getSimOperator` and
 * `getNetworkOperator` are open, and Wi-Fi is identified by its resolvers rather than its SSID,
 * because reading the SSID would require location access for a much weaker reason.
 */
internal data class NetworkFingerprint(
    val transport: VpnNetworkType,
    /** MCC+MNC on cellular, a digest of the resolvers on Wi-Fi, blank when unknown. */
    val operator: String,
    val dpiSignature: String
) {
    val key: String
        get() = listOf(transport.name, operator.ifBlank { "unknown" }, dpiSignature.ifBlank { "?" })
            .joinToString("|")
}

/**
 * Compresses probe outcomes into a short, order-independent signature.
 *
 * Two networks that block the same things get the same signature and can share a strategy;
 * a network that starts blocking something new produces a different one and re-runs selection
 * instead of trusting a stale answer.
 */
internal fun dpiSignature(results: List<DiagnosticResult>): String {
    if (results.isEmpty()) return ""
    return results
        .asSequence()
        .map { it.target.id to it.status }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .joinToString(",") { (id, status) ->
            val flag = when (status) {
                DiagnosticStatus.SUCCESS -> "+"
                DiagnosticStatus.FAILED -> "-"
                DiagnosticStatus.INCONCLUSIVE -> "?"
            }
            "$id$flag"
        }
}

/** Wi-Fi has no permission-free stable identifier, so its resolvers stand in for one. */
internal fun resolverDigest(dnsServers: List<String>): String {
    if (dnsServers.isEmpty()) return ""
    val normalised = dnsServers.map(String::trim).filter(String::isNotEmpty).sorted()
    if (normalised.isEmpty()) return ""
    return "dns-%08x".format(normalised.joinToString(",").hashCode())
}

internal data class StrategyMemory(
    val fingerprintKey: String,
    val preset: ZapretPreset,
    /** How many times this preset was confirmed working here; ties break toward the proven one. */
    val successCount: Int,
    val algorithmVersion: Int,
    val updatedAtMillis: Long
)

/**
 * Remembers `fingerprint -> strategy`, bounded and time-limited.
 *
 * Entries expire because censorship changes: a preset that worked three months ago is a guess,
 * not knowledge, and silently trusting it wastes a connection attempt at the worst moment.
 */
internal object StrategyDatabase {
    const val MAX_ENTRIES = 64
    const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun lookup(
        entries: List<StrategyMemory>,
        fingerprintKey: String,
        algorithmVersion: Int,
        nowMillis: Long
    ): StrategyMemory? = entries
        .filter { it.fingerprintKey == fingerprintKey }
        .filter { it.algorithmVersion == algorithmVersion }
        .filter { nowMillis - it.updatedAtMillis in 0..TTL_MILLIS }
        .maxByOrNull { it.successCount }

    fun upsert(
        entries: List<StrategyMemory>,
        entry: StrategyMemory,
        maxEntries: Int = MAX_ENTRIES
    ): List<StrategyMemory> {
        require(maxEntries > 0)
        val previous = entries.firstOrNull {
            it.fingerprintKey == entry.fingerprintKey && it.preset == entry.preset &&
                it.algorithmVersion == entry.algorithmVersion
        }
        val merged = entry.copy(
            successCount = (previous?.successCount ?: 0) + entry.successCount
        )
        return (entries.filterNot { it === previous } + merged).takeLast(maxEntries)
    }

    fun prune(entries: List<StrategyMemory>, nowMillis: Long): List<StrategyMemory> =
        entries.filter { nowMillis - it.updatedAtMillis in 0..TTL_MILLIS }

    /**
     * Merges an imported database into the local one. Imports are advisory: a local entry that
     * has been confirmed more often wins, so importing a stranger's file cannot override what
     * this device measured for itself.
     */
    fun merge(
        local: List<StrategyMemory>,
        imported: List<StrategyMemory>,
        maxEntries: Int = MAX_ENTRIES
    ): List<StrategyMemory> {
        val result = local.toMutableList()
        imported.forEach { candidate ->
            val existing = result.firstOrNull {
                it.fingerprintKey == candidate.fingerprintKey && it.preset == candidate.preset &&
                    it.algorithmVersion == candidate.algorithmVersion
            }
            when {
                existing == null -> result += candidate
                candidate.successCount > existing.successCount -> {
                    result[result.indexOf(existing)] = candidate
                }
            }
        }
        return result.takeLast(maxEntries)
    }

    fun encode(entries: List<StrategyMemory>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            if (entry.fingerprintKey.isBlank() || entry.preset == ZapretPreset.AUTO) return@forEach
            array.put(
                JSONObject()
                    .put("fingerprint", entry.fingerprintKey)
                    .put("preset", entry.preset.name)
                    .put("successCount", entry.successCount)
                    .put("algorithmVersion", entry.algorithmVersion)
                    .put("updatedAt", entry.updatedAtMillis)
            )
        }
        return JSONObject().put("version", FORMAT_VERSION).put("entries", array).toString()
    }

    fun decode(raw: String?, maxEntries: Int = MAX_ENTRIES): List<StrategyMemory> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("entries") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val fingerprint = item.optString("fingerprint")
                if (fingerprint.isBlank()) return@mapNotNull null
                val preset = ZapretPreset.fromName(item.optString("preset"))
                    .takeUnless { it == ZapretPreset.AUTO } ?: return@mapNotNull null
                StrategyMemory(
                    fingerprintKey = fingerprint,
                    preset = preset,
                    successCount = item.optInt("successCount", 0).coerceAtLeast(0),
                    algorithmVersion = item.optInt("algorithmVersion", 0),
                    updatedAtMillis = item.optLong("updatedAt", 0L)
                )
            }.takeLast(maxEntries)
        }.getOrDefault(emptyList())
    }

    const val FORMAT_VERSION = 1
}
