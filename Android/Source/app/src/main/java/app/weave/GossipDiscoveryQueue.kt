package app.weave

/**
 * Untrusted gossip is only a lookup lead, never a verified profile or app-root binding.
 * This account-scoped, in-memory queue is bounded against repeated or malicious gossip.
 * It is reset whenever the daemon identity changes; authoritative discovery state is
 * restored separately from the encrypted vault.
 */
internal enum class GossipLeadKind(val priority: Int) {
    UNREAD_PROFILE(4),
    GROUP_AUTHORITY(5),
    MISSING_DOCUMENT(3),
    NEWER_PROFILE(2),
    GROUP_DIRECTORY(1),
}

internal data class GossipDiscoveryLead(
    val mainDht: String,
    val advertisedRoot: String,
    val kind: GossipLeadKind,
    val generation: Long,
    val updatedAt: Long,
    val receivedAtMs: Long,
    var nextAttemptAtMs: Long,
    var attempts: Int = 0,
)

internal class GossipDiscoveryQueue(private val capacity: Int = 256) {
    private val leads = LinkedHashMap<String, GossipDiscoveryLead>()
    private val expiryMs = 30 * 60_000L
    private val retryMs = 90_000L

    @Synchronized fun clear() = leads.clear()
    @Synchronized fun size() = leads.size
    @Synchronized fun forget(mainDht: String) { leads.remove(mainDht) }
    @Synchronized fun defer(mainDht: String, untilMs: Long) {
        leads[mainDht]?.nextAttemptAtMs = untilMs
    }

    @Synchronized fun offer(
        mainDht: String,
        advertisedRoot: String,
        kind: GossipLeadKind,
        generation: Long,
        updatedAt: Long,
        nowMs: Long,
    ): Boolean {
        // DHT keys are public identifiers. Reject arbitrary strings before even attempting
        // an expensive network operation; the daemon performs authoritative validation.
        if (mainDht.length !in 20..256 || !mainDht.startsWith("VLD0:") ||
            !mainDht.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ':' || it == '_' || it == '-' }) return false
        prune(nowMs)
        val current = leads[mainDht]
        if (current != null) {
            val promoted = kind.priority > current.kind.priority || generation > current.generation ||
                updatedAt > current.updatedAt || (advertisedRoot.isNotBlank() && advertisedRoot != current.advertisedRoot)
            if (!promoted) return false
            leads[mainDht] = GossipDiscoveryLead(mainDht,
                advertisedRoot.ifBlank { current.advertisedRoot },
                if (kind.priority > current.kind.priority) kind else current.kind,
                maxOf(generation, current.generation), maxOf(updatedAt, current.updatedAt),
                nowMs, if (generation > current.generation || updatedAt > current.updatedAt) nowMs else current.nextAttemptAtMs,
                if (generation > current.generation || updatedAt > current.updatedAt) 0 else current.attempts)
            return true
        }
        if (leads.size >= capacity) {
            val victim = leads.values.minWithOrNull(compareBy<GossipDiscoveryLead> { it.kind.priority }
                .thenBy { it.receivedAtMs }) ?: return false
            if (victim.kind.priority > kind.priority) return false
            leads.remove(victim.mainDht)
        }
        leads[mainDht] = GossipDiscoveryLead(mainDht, advertisedRoot, kind, generation, updatedAt, nowMs, nowMs)
        return true
    }

    @Synchronized fun takeDue(nowMs: Long, limit: Int): List<GossipDiscoveryLead> {
        prune(nowMs)
        val due = leads.values.asSequence().filter { it.nextAttemptAtMs <= nowMs }
            .sortedWith(compareByDescending<GossipDiscoveryLead> { it.kind.priority }
                .thenBy { it.receivedAtMs })
            .take(limit.coerceIn(0, 16)).toList()
        due.forEach {
            it.attempts++
            it.nextAttemptAtMs = nowMs + retryMs
        }
        return due.map { it.copy() }
    }

    @Synchronized private fun prune(nowMs: Long) {
        val it = leads.values.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().receivedAtMs > expiryMs) it.remove()
        }
    }
}
