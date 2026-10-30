package app.weave

import java.security.MessageDigest

/** Deterministic, bounded selection; no group identities are sent by this helper. */
internal data class GroupCustodyTargetPlanV2(
    val initial: List<String>,
    val standby: List<String>,
) {
    val total: Int get() = initial.size + standby.size
}

/**
 * Only cryptographically verified, unexpired custody receipts qualify a peer
 * as known to retain this group's events. Other discovered peers are backups,
 * NOT assumed members. Callers enforce group visibility and exclusion policy.
 */
internal fun planGroupCustodyTargetsV2(
    candidates: List<String>,
    provenGroupCustodians: Set<String>,
    preference: (String) -> Int,
    eventId: String,
    initialTargetCount: Int = 3,
    maximumTargetCount: Int = 6,
): GroupCustodyTargetPlanV2 {
    val limit = maximumTargetCount.coerceIn(0, 6)
    val initialLimit = initialTargetCount.coerceIn(0, limit)
    val selected = candidates.asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .sortedWith(
            compareByDescending<String> { it in provenGroupCustodians }
                .thenByDescending { preference(it) }
                .thenBy { peer ->
                    val bytes = MessageDigest.getInstance("SHA-256")
                        .digest("$eventId|$peer".toByteArray(Charsets.UTF_8))
                    bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
        )
        .take(limit)
        .toList()
    return GroupCustodyTargetPlanV2(
        initial = selected.take(initialLimit),
        standby = selected.drop(initialLimit),
    )
}
