package app.weave

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Small in-memory Groups-v2 abuse guard.
 *
 * This is deliberately availability-only protection, not reputation. Exceeding a rate limit does
 * not create negative reputation evidence and does not ban a peer; it only drops excess work until
 * the sliding window drains. Limits reset when Weave restarts.
 */
enum class GroupAbuseActionV2(
    val windowMs: Long,
    val perPeerLimit: Int,
    val perPeerGroupLimit: Int,
) {
    AuthenticatedEvent(10L * 60L * 1000L, 600, 300),
    OpenServiceRequest(10L * 60L * 1000L, 180, 180),
    PublicWitness(10L * 60L * 1000L, 180, 120),
    CustodyStoreRequest(10L * 60L * 1000L, 180, 90),
    CustodyReceipt(10L * 60L * 1000L, 240, 120),
    RecoveryRequest(10L * 60L * 1000L, 90, 30),
    RecoveryBatch(10L * 60L * 1000L, 120, 60),
}

class GroupAbuseGuardV2(
    private val logger: (String) -> Unit = {},
) {
    private data class Bucket(
        val times: ArrayDeque<Long> = ArrayDeque(),
        var lastTouchedAt: Long = 0L,
    )

    private val buckets = LinkedHashMap<String, Bucket>()
    private val rejected = linkedMapOf<GroupAbuseActionV2, Long>()
    private var lastPruneAt = 0L

    @Synchronized
    fun allow(
        action: GroupAbuseActionV2,
        peerMainDht: String,
        groupId: String = "",
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (peerMainDht.isBlank()) return false
        maybePrune(now)
        val peerKey = "${action.name}|p|$peerMainDht"
        if (!consume(peerKey, action.windowMs, action.perPeerLimit, now)) {
            reject(action, peerMainDht, groupId, "peer-rate")
            return false
        }
        if (groupId.isNotBlank()) {
            val groupKey = "${action.name}|g|$peerMainDht|$groupId"
            if (!consume(groupKey, action.windowMs, action.perPeerGroupLimit, now)) {
                // Do not let the failed group-specific check consume a peer-wide slot forever.
                buckets[peerKey]?.times?.pollLast()
                reject(action, peerMainDht, groupId, "peer-group-rate")
                return false
            }
        }
        return true
    }

    @Synchronized
    fun diagnosticLines(): List<String> {
        val totalRejected = rejected.values.sum()
        return buildList {
            add("abuse guard buckets: ${buckets.size}  rejected: $totalRejected")
            rejected.entries.sortedBy { it.key.name }.forEach { (action, count) ->
                add("rate-limit ${action.name}: rejected=$count window=${action.windowMs / 1000}s peer=${action.perPeerLimit} peer-group=${action.perPeerGroupLimit}")
            }
        }
    }

    private fun consume(key: String, windowMs: Long, limit: Int, now: Long): Boolean {
        val bucket = buckets.getOrPut(key) { Bucket() }
        bucket.lastTouchedAt = now
        val cutoff = now - windowMs
        while (bucket.times.isNotEmpty() && bucket.times.peekFirst() < cutoff) bucket.times.removeFirst()
        if (bucket.times.size >= limit) return false
        bucket.times.addLast(now)
        return true
    }

    private fun reject(action: GroupAbuseActionV2, peer: String, groupId: String, reason: String) {
        val count = (rejected[action] ?: 0L) + 1L
        rejected[action] = count
        // Log the first rejection and then powers of two to avoid creating a log-flood primitive.
        if (count == 1L || count and (count - 1L) == 0L) {
            logger("[groups-v2] ABUSE_RATE_LIMIT action=${action.name} peer=${shortAbuse(peer)} group=${groupId.take(8)} reason=$reason rejected=$count reputation_penalty=false")
        }
    }

    private fun maybePrune(now: Long) {
        if (now - lastPruneAt < PRUNE_INTERVAL_MS && buckets.size <= MAX_BUCKETS) return
        lastPruneAt = now
        val staleBefore = now - MAX_BUCKET_AGE_MS
        buckets.entries.removeAll { it.value.lastTouchedAt < staleBefore || it.value.times.isEmpty() }
        if (buckets.size <= MAX_BUCKETS) return
        buckets.entries
            .sortedBy { it.value.lastTouchedAt }
            .take(buckets.size - MAX_BUCKETS)
            .map { it.key }
            .forEach(buckets::remove)
    }

    companion object {
        private const val MAX_BUCKETS = 2048
        private const val PRUNE_INTERVAL_MS = 60_000L
        private const val MAX_BUCKET_AGE_MS = 30L * 60L * 1000L
    }
}

private fun shortAbuse(value: String): String = when {
    value.length <= 18 -> value
    else -> value.take(9) + "…" + value.takeLast(6)
}
