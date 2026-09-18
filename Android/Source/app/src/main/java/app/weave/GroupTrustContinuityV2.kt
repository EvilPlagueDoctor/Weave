package app.weave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

private const val GROUP_TRUST_VAULT_KEY = "groups_v2/trust_continuity_v1"
private const val AUTHORITY_QUIET_AFTER_MS = 24L * 60L * 60L * 1000L
private const val AUTHORITY_CLAIM_AFTER_MS = 48L * 60L * 60L * 1000L
private const val MAX_REPUTATION_EVIDENCE = 512
private const val MAX_TRACKED_PRESENCE = 512
private const val MAX_AUTHORITY_WATCHES = 512
private const val PRESENCE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
private const val WATCH_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
private const val PRESENCE_UPDATE_INTERVAL_MS = 60_000L

enum class GroupReputationEvidenceKindV2(
    val scoreDelta: Int,
    val strong: Boolean = false,
    val quarantine: Boolean = false,
) {
    CustodyReceiptVerified(+1),
    RecoveryCanonicalVerified(+2),
    RecoveryContentVerified(+3),
    CustodyReceiptHonored(+5),
    GossipDhtConfirmed(+1),
    CrossTransportCanonicalMatch(+1),

    InvalidCustodyReceiptSignature(-8, strong = true),
    InvalidRecoveryBatchSignature(-10, strong = true),
    InvalidRecoveredEvent(-12, strong = true),
    CustodyReceiptDigestContradiction(-35, strong = true, quarantine = true),
    AuthenticatedEventEquivocation(-50, strong = true, quarantine = true),
}

data class GroupReputationEvidenceV2(
    val key: String,
    val peerMainDht: String,
    val kind: GroupReputationEvidenceKindV2,
    val observedAt: Long,
    val groupId: String = "",
    val eventId: String = "",
    val detail: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("peer", peerMainDht)
        .put("kind", kind.name)
        .put("observed_at", observedAt)
        .put("group_id", groupId)
        .put("event_id", eventId)
        .put("detail", detail)

    companion object {
        fun fromJson(o: JSONObject): GroupReputationEvidenceV2? {
            val kind = runCatching { GroupReputationEvidenceKindV2.valueOf(o.getString("kind")) }.getOrNull() ?: return null
            val peer = o.optString("peer")
            val key = o.optString("key")
            if (peer.isBlank() || key.isBlank()) return null
            return GroupReputationEvidenceV2(
                key = key,
                peerMainDht = peer,
                kind = kind,
                observedAt = o.optLong("observed_at"),
                groupId = o.optString("group_id"),
                eventId = o.optString("event_id"),
                detail = o.optString("detail"),
            )
        }
    }
}

data class GroupPeerPresenceV2(
    val peerMainDht: String,
    var firstLiveSeenAt: Long,
    var lastLiveSeenAt: Long,
    var lastSignal: String,
    var signalCount: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("peer", peerMainDht)
        .put("first_live_seen_at", firstLiveSeenAt)
        .put("last_live_seen_at", lastLiveSeenAt)
        .put("last_signal", lastSignal)
        .put("signal_count", signalCount)

    companion object {
        fun fromJson(o: JSONObject): GroupPeerPresenceV2? {
            val peer = o.optString("peer")
            if (peer.isBlank()) return null
            return GroupPeerPresenceV2(
                peerMainDht = peer,
                firstLiveSeenAt = o.optLong("first_live_seen_at"),
                lastLiveSeenAt = o.optLong("last_live_seen_at"),
                lastSignal = o.optString("last_signal"),
                signalCount = o.optInt("signal_count", 1),
            )
        }
    }
}

data class GroupAuthorityWatchV2(
    val groupId: String,
    val branchId: String,
    val trackingSince: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("branch_id", branchId)
        .put("tracking_since", trackingSince)

    companion object {
        fun fromJson(o: JSONObject): GroupAuthorityWatchV2? {
            val groupId = o.optString("group_id")
            val branchId = o.optString("branch_id")
            if (groupId.isBlank() || branchId.isBlank()) return null
            return GroupAuthorityWatchV2(groupId, branchId, o.optLong("tracking_since"))
        }
    }
}

enum class GroupAuthorityContinuityLevelV2 {
    OwnAuthority,
    Watching,
    RecentlySeen,
    QuietConcern,
    ClaimRecommended,
}

data class GroupAuthorityContinuityV2(
    val level: GroupAuthorityContinuityLevelV2,
    val authorityMainDht: String,
    val lastLiveSeenAt: Long,
    val trackingSince: Long,
    val absentForMs: Long,
    val hasPendingOrRecentActivity: Boolean,
    val message: String,
)

data class GroupPeerReputationSummaryV2(
    val peerMainDht: String,
    val score: Int,
    val positives: Int,
    val negatives: Int,
    val strongNegatives: Int,
    val quarantined: Boolean,
    val lastEvidenceAt: Long,
)

/**
 * Encrypted, account-scoped Groups-v2 trust evidence and weak authority-presence tracking.
 *
 * This deliberately does NOT submit daemon/network reputation. Availability is not honesty:
 * offline time, route failures, unanswered recovery requests and cache expiry are never evidence.
 */
class GroupTrustContinuityStoreV2(
    context: Context,
    private val logger: (String) -> Unit = {},
) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context.applicationContext)
    private val evidence = LinkedHashMap<String, GroupReputationEvidenceV2>()
    private val presence = linkedMapOf<String, GroupPeerPresenceV2>()
    private val watches = linkedMapOf<String, GroupAuthorityWatchV2>()

    init { vault.register(this) }

    override fun onVaultAttached() = load()
    override fun onVaultDetached() = synchronized(this) {
        evidence.clear(); presence.clear(); watches.clear()
    }

    @Synchronized
    fun recordEvidence(
        peerMainDht: String,
        kind: GroupReputationEvidenceKindV2,
        evidenceKey: String,
        groupId: String = "",
        eventId: String = "",
        detail: String = "",
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (peerMainDht.isBlank() || evidenceKey.isBlank()) return false
        val key = "$peerMainDht|${kind.name}|$evidenceKey"
        if (evidence.containsKey(key)) return false
        evidence[key] = GroupReputationEvidenceV2(
            key = key,
            peerMainDht = peerMainDht,
            kind = kind,
            observedAt = now,
            groupId = groupId,
            eventId = eventId,
            detail = detail.take(240),
        )
        while (evidence.size > MAX_REPUTATION_EVIDENCE) {
            evidence.entries.firstOrNull()?.key?.let(evidence::remove) ?: break
        }
        persist()
        val summary = reputation(peerMainDht)
        logger("[groups-v2] REPUTATION_EVIDENCE peer=${shortTrust(peerMainDht)} kind=${kind.name} delta=${kind.scoreDelta} score=${summary.score} strong=${kind.strong} quarantined=${summary.quarantined}")
        return true
    }

    @Synchronized
    fun reputation(peerMainDht: String): GroupPeerReputationSummaryV2 {
        val items = evidence.values.filter { it.peerMainDht == peerMainDht }
        // Positive service evidence is intentionally capped so reputation cannot be farmed into
        // immunity by generating lots of harmless traffic. Strong negative proof is never offset
        // beyond this small positive ceiling.
        val score = items.sumOf { it.kind.scoreDelta }.coerceIn(-100, 25)
        return GroupPeerReputationSummaryV2(
            peerMainDht = peerMainDht,
            score = score,
            positives = items.count { it.kind.scoreDelta > 0 },
            negatives = items.count { it.kind.scoreDelta < 0 },
            strongNegatives = items.count { it.kind.strong },
            quarantined = items.any { it.kind.quarantine },
            lastEvidenceAt = items.maxOfOrNull { it.observedAt } ?: 0L,
        )
    }

    @Synchronized
    fun protocolEligible(peerMainDht: String): Boolean = !reputation(peerMainDht).quarantined

    @Synchronized
    fun protocolPreference(peerMainDht: String): Int = reputation(peerMainDht).score

    @Synchronized
    fun observePeerLive(peerMainDht: String, signal: String, now: Long = System.currentTimeMillis()) {
        if (peerMainDht.isBlank()) return
        pruneTracking(now)
        val existing = presence[peerMainDht]
        if (existing == null) {
            if (presence.size >= MAX_TRACKED_PRESENCE) {
                presence.entries.minByOrNull { it.value.lastLiveSeenAt }?.key?.let(presence::remove)
            }
            presence[peerMainDht] = GroupPeerPresenceV2(peerMainDht, now, now, signal, 1)
            persist()
            return
        }
        // Group gossip can arrive every few seconds; minute-level persistence is more than enough
        // for 24/48-hour continuity decisions and avoids hammering encrypted storage.
        if (now - existing.lastLiveSeenAt < PRESENCE_UPDATE_INTERVAL_MS) return
        existing.lastLiveSeenAt = now
        existing.lastSignal = signal.take(80)
        existing.signalCount++
        persist()
    }

    @Synchronized
    fun ensureWatch(groupId: String, branchId: String, now: Long = System.currentTimeMillis()): GroupAuthorityWatchV2 {
        pruneTracking(now)
        val key = "$groupId|$branchId"
        val existing = watches[key]
        if (existing != null) return existing
        if (watches.size >= MAX_AUTHORITY_WATCHES) {
            watches.entries.minByOrNull { it.value.trackingSince }?.key?.let(watches::remove)
        }
        return GroupAuthorityWatchV2(groupId, branchId, now).also {
            watches[key] = it
            persist()
        }
    }

    @Synchronized
    fun continuity(
        groupId: String,
        branchId: String,
        authorityPeers: Collection<String>,
        ownMainDht: String,
        hasPendingOrRecentActivity: Boolean,
        now: Long = System.currentTimeMillis(),
    ): GroupAuthorityContinuityV2 {
        // Read-only calculation: Compose/UI callers must not perform vault writes. The durable
        // watch is established by branch refresh/discovery on the controller's IO coroutine.
        val watch = watches["$groupId|$branchId"] ?: GroupAuthorityWatchV2(groupId, branchId, now)
        val peers = authorityPeers.filter(String::isNotBlank).distinct()
        if (ownMainDht.isNotBlank() && ownMainDht in peers) {
            return GroupAuthorityContinuityV2(
                level = GroupAuthorityContinuityLevelV2.OwnAuthority,
                authorityMainDht = ownMainDht,
                lastLiveSeenAt = now,
                trackingSince = watch.trackingSince,
                absentForMs = 0L,
                hasPendingOrRecentActivity = hasPendingOrRecentActivity,
                message = "You are part of this moderation branch's authority.",
            )
        }
        val latest = peers.mapNotNull { presence[it] }.maxByOrNull { it.lastLiveSeenAt }
        val baseline = maxOf(watch.trackingSince, latest?.lastLiveSeenAt ?: 0L)
        val absentFor = (now - baseline).coerceAtLeast(0L)
        val level = when {
            absentFor < AUTHORITY_QUIET_AFTER_MS && latest != null -> GroupAuthorityContinuityLevelV2.RecentlySeen
            absentFor < AUTHORITY_QUIET_AFTER_MS -> GroupAuthorityContinuityLevelV2.Watching
            absentFor >= AUTHORITY_CLAIM_AFTER_MS && hasPendingOrRecentActivity -> GroupAuthorityContinuityLevelV2.ClaimRecommended
            else -> GroupAuthorityContinuityLevelV2.QuietConcern
        }
        val label = if (peers.size > 1) "This moderation branch's authorities" else "This moderation authority"
        val message = when (level) {
            GroupAuthorityContinuityLevelV2.RecentlySeen -> "$label have been seen recently."
            GroupAuthorityContinuityLevelV2.Watching -> "Watching this moderation branch for continuity. No absence warning yet."
            GroupAuthorityContinuityLevelV2.QuietConcern -> "$label haven't been seen recently. This may be temporary; no reputation penalty is applied."
            GroupAuthorityContinuityLevelV2.ClaimRecommended -> "$label haven't been seen for roughly two days while group activity is waiting. You can create an independent Claim moderation branch without replacing the existing branch."
            GroupAuthorityContinuityLevelV2.OwnAuthority -> "You are part of this moderation branch's authority."
        }
        return GroupAuthorityContinuityV2(
            level = level,
            authorityMainDht = latest?.peerMainDht ?: peers.firstOrNull().orEmpty(),
            lastLiveSeenAt = latest?.lastLiveSeenAt ?: 0L,
            trackingSince = watch.trackingSince,
            absentForMs = absentFor,
            hasPendingOrRecentActivity = hasPendingOrRecentActivity,
            message = message,
        )
    }

    @Synchronized
    fun diagnosticLines(): List<String> {
        val out = mutableListOf<String>()
        val peers = (evidence.values.map { it.peerMainDht } + presence.keys).distinct().sorted()
        out += "tracked peers: ${peers.size}  evidence: ${evidence.size}  authority watches: ${watches.size}"
        peers.take(40).forEach { peer ->
            val r = reputation(peer)
            val p = presence[peer]
            out += "peer ${shortTrust(peer)} | score=${r.score} +${r.positives}/-${r.negatives} strong=${r.strongNegatives} quarantined=${r.quarantined} last_live=${p?.lastLiveSeenAt ?: 0L} signal=${p?.lastSignal.orEmpty()}"
        }
        return out
    }

    private fun pruneTracking(now: Long) {
        presence.entries.removeAll { (_, item) -> item.lastLiveSeenAt in 1 until (now - PRESENCE_RETENTION_MS) }
        watches.entries.removeAll { (_, item) -> item.trackingSince in 1 until (now - WATCH_RETENTION_MS) }
        while (presence.size > MAX_TRACKED_PRESENCE) {
            presence.entries.minByOrNull { it.value.lastLiveSeenAt }?.key?.let(presence::remove) ?: break
        }
        while (watches.size > MAX_AUTHORITY_WATCHES) {
            watches.entries.minByOrNull { it.value.trackingSince }?.key?.let(watches::remove) ?: break
        }
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) return
        val root = JSONObject()
            .put("version", 1)
            .put("evidence", JSONArray().apply { evidence.values.forEach { put(it.toJson()) } })
            .put("presence", JSONArray().apply { presence.values.forEach { put(it.toJson()) } })
            .put("watches", JSONArray().apply { watches.values.forEach { put(it.toJson()) } })
        vault.putText(GROUP_TRUST_VAULT_KEY, root.toString())
    }

    @Synchronized
    private fun load() {
        evidence.clear(); presence.clear(); watches.clear()
        val raw = vault.getText(GROUP_TRUST_VAULT_KEY) ?: return
        runCatching {
            val root = JSONObject(raw)
            val ea = root.optJSONArray("evidence") ?: JSONArray()
            repeat(ea.length()) { ea.optJSONObject(it)?.let(GroupReputationEvidenceV2::fromJson)?.let { item -> evidence[item.key] = item } }
            val pa = root.optJSONArray("presence") ?: JSONArray()
            repeat(pa.length()) { pa.optJSONObject(it)?.let(GroupPeerPresenceV2::fromJson)?.let { item -> presence[item.peerMainDht] = item } }
            val wa = root.optJSONArray("watches") ?: JSONArray()
            repeat(wa.length()) { wa.optJSONObject(it)?.let(GroupAuthorityWatchV2::fromJson)?.let { item -> watches["${item.groupId}|${item.branchId}"] = item } }
            pruneTracking(System.currentTimeMillis())
        }.onFailure {
            logger("[groups-v2] TRUST_STORE_LOAD_FAILED ${it.message.orEmpty().take(160)}")
        }
    }
}

private fun shortTrust(value: String): String = when {
    value.length <= 18 -> value
    else -> value.take(9) + "…" + value.takeLast(6)
}
