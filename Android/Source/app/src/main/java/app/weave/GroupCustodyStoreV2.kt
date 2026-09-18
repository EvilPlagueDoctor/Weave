package app.weave

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** Logical local record for one canonical event retained as a group custodian. */
data class CustodyEventRecordV2(
    val groupId: String,
    val eventId: String,
    val canonicalDigestHex: String,
    val canonicalEventBytes: ByteArray,
    val authorMainDht: String,
    val eventCreatedAt: Long,
    val eventExpiresAt: Long,
    var firstStoredAt: Long,
    var lastConfirmedAt: Long,
    var retainUntil: Long,
    var retentionClass: CustodyRetentionClassV2 = CustodyRetentionClassV2.Requested,
    val custodySources: MutableSet<String> = linkedSetOf(),
    var validation: GroupEventValidationV2 = GroupEventValidationV2.Valid,
    var contentVerifiedAt: Long = 0L,
    var receiptIssued: Boolean = false,
) {
    fun event(): SignedGroupEventV2? = GroupEventWitnessCodecV2.decode(canonicalEventBytes)

    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("event_id", eventId)
        .put("canonical_digest_hex", canonicalDigestHex)
        .put("canonical_event_base64", Base64.encodeToString(canonicalEventBytes, Base64.NO_WRAP))
        .put("author_main_dht", authorMainDht)
        .put("event_created_at", eventCreatedAt)
        .put("event_expires_at", eventExpiresAt)
        .put("first_stored_at", firstStoredAt)
        .put("last_confirmed_at", lastConfirmedAt)
        .put("retain_until", retainUntil)
        .put("retention_class", retentionClass.name)
        .put("custody_sources", JSONArray(custodySources.toList().sorted()))
        .put("validation", validation.name)
        .put("content_verified_at", contentVerifiedAt)
        .put("receipt_issued", receiptIssued)

    companion object {
        fun fromJson(o: JSONObject): CustodyEventRecordV2 {
            val sources = linkedSetOf<String>()
            val a = o.optJSONArray("custody_sources") ?: JSONArray()
            repeat(a.length()) { a.optString(it).takeIf(String::isNotBlank)?.let(sources::add) }
            return CustodyEventRecordV2(
                groupId = o.getString("group_id"),
                eventId = o.getString("event_id"),
                canonicalDigestHex = o.getString("canonical_digest_hex"),
                canonicalEventBytes = Base64.decode(o.getString("canonical_event_base64"), Base64.DEFAULT),
                authorMainDht = o.getString("author_main_dht"),
                eventCreatedAt = o.getLong("event_created_at"),
                eventExpiresAt = o.getLong("event_expires_at"),
                firstStoredAt = o.getLong("first_stored_at"),
                lastConfirmedAt = o.getLong("last_confirmed_at"),
                retainUntil = o.getLong("retain_until"),
                retentionClass = runCatching { CustodyRetentionClassV2.valueOf(o.optString("retention_class")) }
                    .getOrDefault(CustodyRetentionClassV2.Requested),
                custodySources = sources,
                validation = runCatching { GroupEventValidationV2.valueOf(o.optString("validation")) }
                    .getOrDefault(GroupEventValidationV2.Valid),
                contentVerifiedAt = o.optLong("content_verified_at"),
                receiptIssued = o.optBoolean("receipt_issued"),
            )
        }
    }
}

data class CustodyReceiptRecordV2(
    val eventId: String,
    val groupId: String,
    val canonicalDigestHex: String,
    val custodianMainDht: String,
    val storedAt: Long,
    val retainUntil: Long,
    val receiptBytes: ByteArray,
    val verified: Boolean,
    var firstSeenAt: Long,
    var lastSeenAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("event_id", eventId)
        .put("group_id", groupId)
        .put("canonical_digest_hex", canonicalDigestHex)
        .put("custodian_main_dht", custodianMainDht)
        .put("stored_at", storedAt)
        .put("retain_until", retainUntil)
        .put("receipt_base64", Base64.encodeToString(receiptBytes, Base64.NO_WRAP))
        .put("verified", verified)
        .put("first_seen_at", firstSeenAt)
        .put("last_seen_at", lastSeenAt)

    companion object {
        fun fromJson(o: JSONObject) = CustodyReceiptRecordV2(
            eventId = o.getString("event_id"),
            groupId = o.getString("group_id"),
            canonicalDigestHex = o.getString("canonical_digest_hex"),
            custodianMainDht = o.getString("custodian_main_dht"),
            storedAt = o.getLong("stored_at"),
            retainUntil = o.getLong("retain_until"),
            receiptBytes = Base64.decode(o.getString("receipt_base64"), Base64.DEFAULT),
            verified = o.optBoolean("verified"),
            firstSeenAt = o.getLong("first_seen_at"),
            lastSeenAt = o.getLong("last_seen_at"),
        )
    }
}

data class CustodyRecoveryCursorV2(
    val groupId: String,
    val branchId: String,
    var lastRecoveryStartedAt: Long = 0L,
    var lastRecoveryCompletedAt: Long = 0L,
    var newestRecoveredCreatedAt: Long = 0L,
    var newestRecoveredEventId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("branch_id", branchId)
        .put("last_recovery_started_at", lastRecoveryStartedAt)
        .put("last_recovery_completed_at", lastRecoveryCompletedAt)
        .put("newest_recovered_created_at", newestRecoveredCreatedAt)
        .put("newest_recovered_event_id", newestRecoveredEventId)

    companion object {
        fun fromJson(o: JSONObject) = CustodyRecoveryCursorV2(
            groupId = o.getString("group_id"),
            branchId = o.getString("branch_id"),
            lastRecoveryStartedAt = o.optLong("last_recovery_started_at"),
            lastRecoveryCompletedAt = o.optLong("last_recovery_completed_at"),
            newestRecoveredCreatedAt = o.optLong("newest_recovered_created_at"),
            newestRecoveredEventId = o.optString("newest_recovered_event_id"),
        )
    }
}

data class CustodyRequestReplayRecordV2(
    val requestId: String,
    val requesterMainDht: String,
    val firstSeenAt: Long,
    val expiresAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("request_id", requestId)
        .put("requester_main_dht", requesterMainDht)
        .put("first_seen_at", firstSeenAt)
        .put("expires_at", expiresAt)

    companion object {
        fun fromJson(o: JSONObject) = CustodyRequestReplayRecordV2(
            requestId = o.getString("request_id"),
            requesterMainDht = o.getString("requester_main_dht"),
            firstSeenAt = o.getLong("first_seen_at"),
            expiresAt = o.getLong("expires_at"),
        )
    }
}

data class GroupBranchDecisionRecordV2(
    val groupId: String,
    val branchId: String,
    val eventId: String,
    var state: GroupBranchEventStateV2,
    val firstSeenAt: Long,
    var lastUpdatedAt: Long,
    var decidedAt: Long = 0L,
    var decidedByMainDht: String = "",
    var decisionGeneration: Long = 1L,
    var signedDecisionBytes: ByteArray? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("branch_id", branchId)
        .put("event_id", eventId)
        .put("state", state.name)
        .put("first_seen_at", firstSeenAt)
        .put("last_updated_at", lastUpdatedAt)
        .put("decided_at", decidedAt)
        .put("decided_by_main_dht", decidedByMainDht)
        .put("decision_generation", decisionGeneration)
        .put("signed_decision_base64", signedDecisionBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())

    companion object {
        fun fromJson(o: JSONObject) = GroupBranchDecisionRecordV2(
            groupId = o.getString("group_id"),
            branchId = o.getString("branch_id"),
            eventId = o.getString("event_id"),
            state = runCatching { GroupBranchEventStateV2.valueOf(o.getString("state")) }
                .getOrDefault(GroupBranchEventStateV2.Unseen),
            firstSeenAt = o.getLong("first_seen_at"),
            lastUpdatedAt = o.getLong("last_updated_at"),
            decidedAt = o.optLong("decided_at"),
            decidedByMainDht = o.optString("decided_by_main_dht"),
            decisionGeneration = o.optLong("decision_generation", 1L),
            signedDecisionBytes = o.optString("signed_decision_base64").takeIf(String::isNotBlank)
                ?.let { Base64.decode(it, Base64.DEFAULT) },
        )
    }
}

data class CustodyRecoveryPageV2(
    val events: List<SignedGroupEventV2>,
    val moreAvailable: Boolean,
    val nextCursor: String,
)

/**
 * Account-scoped encrypted custody/recovery state. One versioned vault value is used so updates to
 * event retention, receipts, recovery cursors and branch decisions commit atomically.
 */
class GroupCustodyStoreV2(
    context: Context,
    private val logger: (String) -> Unit = {},
) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context.applicationContext)
    private val events = linkedMapOf<String, CustodyEventRecordV2>()
    private val receipts = linkedMapOf<String, CustodyReceiptRecordV2>()
    private val cursors = linkedMapOf<String, CustodyRecoveryCursorV2>()
    private val replay = linkedMapOf<String, CustodyRequestReplayRecordV2>()
    private val decisions = linkedMapOf<String, GroupBranchDecisionRecordV2>()

    init { vault.register(this) }

    override fun onVaultAttached() = load()
    override fun onVaultDetached() = synchronized(this) {
        events.clear(); receipts.clear(); cursors.clear(); replay.clear(); decisions.clear()
    }

    @Synchronized
    fun storeEvent(
        event: SignedGroupEventV2,
        sourceMainDht: String,
        requestedRetainUntil: Long,
        now: Long = System.currentTimeMillis(),
    ): CustodyEventRecordV2? {
        cleanup(now)
        if (event.expiresAt in 1..now) return null
        val maximum = min(event.expiresAt.takeIf { it > 0L } ?: Long.MAX_VALUE, now + GROUP_EVENT_RETENTION_MS_V2)
        val requested = requestedRetainUntil.takeIf { it > now } ?: maximum
        val retainUntil = min(requested, maximum)
        val retentionClass = if (requested > maximum) CustodyRetentionClassV2.LocallyCapped else CustodyRetentionClassV2.Requested
        if (retainUntil <= now) return null
        val digest = event.canonicalDigestHex()
        val existing = events[event.eventId]
        if (existing != null) {
            if (!existing.canonicalDigestHex.equals(digest, true)) {
                logger("[groups-v2] CUSTODY_CONFLICT event=${event.eventId.take(8)} existing=${existing.canonicalDigestHex.take(16)} incoming=${digest.take(16)}")
                return null
            }
            existing.lastConfirmedAt = now
            existing.retainUntil = maxOf(existing.retainUntil, retainUntil).coerceAtMost(maximum)
            if (retentionClass == CustodyRetentionClassV2.LocallyCapped) existing.retentionClass = retentionClass
            sourceMainDht.takeIf(String::isNotBlank)?.let(existing.custodySources::add)
            persist()
            logger("[groups-v2] CUSTODY_DUPLICATE event=${event.eventId.take(8)} retain_until=${existing.retainUntil}")
            return existing
        }

        val admissionReason = capacityRejectReason(event.groupId, event.authorMainDht)
        if (admissionReason != null) {
            logger("[groups-v2] CUSTODY_REJECT event=${event.eventId.take(8)} group=${event.groupId.take(8)} reason=$admissionReason")
            return null
        }
        val record = CustodyEventRecordV2(
            groupId = event.groupId,
            eventId = event.eventId,
            canonicalDigestHex = digest,
            canonicalEventBytes = GroupEventWitnessCodecV2.encode(event),
            authorMainDht = event.authorMainDht,
            eventCreatedAt = event.createdAt,
            eventExpiresAt = event.expiresAt,
            firstStoredAt = now,
            lastConfirmedAt = now,
            retainUntil = retainUntil,
            retentionClass = retentionClass,
            custodySources = linkedSetOf<String>().apply { sourceMainDht.takeIf(String::isNotBlank)?.let(::add) },
        )
        events[event.eventId] = record
        persist()
        logger("[groups-v2] CUSTODY_STORE event=${event.eventId.take(8)} group=${event.groupId.take(8)} retain_until=$retainUntil total=${events.size}")
        return record
    }

    @Synchronized
    fun markReceiptIssued(eventId: String) {
        val record = events[eventId] ?: return
        if (!record.receiptIssued) {
            record.receiptIssued = true
            persist()
        }
    }

    @Synchronized
    fun rememberReceipt(receipt: CustodyReceiptV2, encoded: ByteArray, verified: Boolean, now: Long = System.currentTimeMillis()) {
        cleanup(now)
        val key = receiptKey(receipt.eventId, receipt.custodianMainDht)
        val existing = receipts[key]
        if (existing == null) {
            if (receipts.values.count { it.eventId == receipt.eventId } >= MAX_RECEIPTS_PER_EVENT) {
                logger("[groups-v2] CUSTODY_RECEIPT_DROP event=${receipt.eventId.take(8)} reason=per-event-cap")
                return
            }
            if (receipts.size >= MAX_TOTAL_RECEIPTS) {
                logger("[groups-v2] CUSTODY_RECEIPT_DROP event=${receipt.eventId.take(8)} reason=total-cap")
                return
            }
        }
        receipts[key] = CustodyReceiptRecordV2(
            eventId = receipt.eventId,
            groupId = receipt.groupId,
            canonicalDigestHex = receipt.canonicalDigestHex,
            custodianMainDht = receipt.custodianMainDht,
            storedAt = receipt.storedAt,
            retainUntil = receipt.retainUntil,
            receiptBytes = encoded,
            verified = verified || existing?.verified == true,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now,
        )
        persist()
        logger("[groups-v2] CUSTODY_RECEIPT event=${receipt.eventId.take(8)} custodian=${short(receipt.custodianMainDht)} retain_until=${receipt.retainUntil} verified=$verified")
    }

    @Synchronized
    fun receiptsForEvent(eventId: String): List<CustodyReceiptRecordV2> =
        receipts.values.filter { it.eventId == eventId }.sortedByDescending { it.retainUntil }

    @Synchronized
    fun verifiedCustodiansForGroup(groupId: String, now: Long = System.currentTimeMillis()): Set<String> =
        receipts.values.asSequence()
            .filter { it.groupId == groupId && it.verified && it.retainUntil > now }
            .map { it.custodianMainDht }
            .filter(String::isNotBlank)
            .toSet()

    @Synchronized
    fun markRecoveryRequestSeen(requestId: String, requesterMainDht: String, now: Long = System.currentTimeMillis()): Boolean {
        pruneReplay(now)
        val key = "$requesterMainDht|$requestId"
        if (replay.containsKey(key)) return false
        if (replay.size >= MAX_REPLAY_RECORDS) {
            replay.entries.minByOrNull { it.value.firstSeenAt }?.key?.let(replay::remove)
        }
        replay[key] = CustodyRequestReplayRecordV2(
            requestId = requestId,
            requesterMainDht = requesterMainDht,
            firstSeenAt = now,
            expiresAt = now + REPLAY_RETENTION_MS,
        )
        persist()
        return true
    }

    @Synchronized
    fun recoveryPage(
        groupId: String,
        sinceCreatedAt: Long,
        untilCreatedAt: Long,
        cursor: String,
        maxEvents: Int,
        now: Long = System.currentTimeMillis(),
    ): CustodyRecoveryPageV2 {
        cleanup(now)
        val cursorParts = cursor.split('|', limit = 2)
        val cursorTime = cursorParts.getOrNull(0)?.toLongOrNull() ?: Long.MIN_VALUE
        val cursorId = cursorParts.getOrNull(1).orEmpty()
        val limit = maxEvents.coerceIn(1, GroupCustodyWireCodecV2.MAX_RECOVERY_EVENTS)
        val eligible = events.values.asSequence()
            .filter { it.groupId == groupId && it.retainUntil > now }
            .filter { it.eventCreatedAt >= sinceCreatedAt && (untilCreatedAt <= 0L || it.eventCreatedAt <= untilCreatedAt) }
            .filter { it.eventCreatedAt > cursorTime || (it.eventCreatedAt == cursorTime && it.eventId > cursorId) }
            .sortedWith(compareBy<CustodyEventRecordV2> { it.eventCreatedAt }.thenBy { it.eventId })
            .toList()
        val selected = eligible.take(limit)
        val decoded = selected.mapNotNull(CustodyEventRecordV2::event)
        val last = selected.lastOrNull()
        return CustodyRecoveryPageV2(
            events = decoded,
            moreAvailable = eligible.size > selected.size,
            nextCursor = last?.let { "${it.eventCreatedAt}|${it.eventId}" }.orEmpty(),
        )
    }

    @Synchronized
    fun markRecoveryStarted(groupId: String, branchId: String, now: Long = System.currentTimeMillis()) {
        val key = cursorKey(groupId, branchId)
        if (key !in cursors && cursors.size >= MAX_RECOVERY_CURSORS) {
            cursors.entries.minByOrNull { maxOf(it.value.lastRecoveryCompletedAt, it.value.lastRecoveryStartedAt) }?.key?.let(cursors::remove)
        }
        val item = cursors.getOrPut(key) { CustodyRecoveryCursorV2(groupId, branchId) }
        item.lastRecoveryStartedAt = now
        persist()
    }

    @Synchronized
    fun markRecoveryCompleted(
        groupId: String,
        branchId: String,
        newestCreatedAt: Long,
        newestEventId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = cursorKey(groupId, branchId)
        val item = cursors.getOrPut(key) { CustodyRecoveryCursorV2(groupId, branchId) }
        item.lastRecoveryCompletedAt = now
        if (newestCreatedAt > item.newestRecoveredCreatedAt ||
            (newestCreatedAt == item.newestRecoveredCreatedAt && newestEventId > item.newestRecoveredEventId)
        ) {
            item.newestRecoveredCreatedAt = newestCreatedAt
            item.newestRecoveredEventId = newestEventId
        }
        persist()
    }

    @Synchronized
    fun recoveryCursor(groupId: String, branchId: String): CustodyRecoveryCursorV2? =
        cursors[cursorKey(groupId, branchId)]?.copy()

    @Synchronized
    fun recordBranchDecision(
        groupId: String,
        branchId: String,
        eventId: String,
        state: GroupBranchEventStateV2,
        decidedByMainDht: String = "",
        signedDecisionBytes: ByteArray? = null,
        now: Long = System.currentTimeMillis(),
    ): GroupBranchDecisionRecordV2 {
        val key = decisionKey(groupId, branchId, eventId)
        val existing = decisions[key]
        if (existing == null) {
            pruneDecisionHistory(now)
            if (decisions.size >= MAX_BRANCH_DECISIONS) {
                val victim = decisions.values
                    .filter { it.state in TERMINAL_STATES }
                    .minByOrNull { it.lastUpdatedAt }
                if (victim != null) decisions.remove(decisionKey(victim.groupId, victim.branchId, victim.eventId))
            }
            val terminal = state in TERMINAL_STATES
            val created = GroupBranchDecisionRecordV2(
                groupId = groupId,
                branchId = branchId,
                eventId = eventId,
                state = state,
                firstSeenAt = now,
                lastUpdatedAt = now,
                decidedAt = if (terminal) now else 0L,
                decidedByMainDht = if (terminal) decidedByMainDht else "",
                decisionGeneration = 1L,
                signedDecisionBytes = signedDecisionBytes,
            )
            decisions[key] = created
            persist()
            logger("[groups-v2] BRANCH_DECISION_STORE event=${eventId.take(8)} branch=${branchId.take(12)} state=${state.name} generation=1")
            return created
        }
        if (existing.state != state || signedDecisionBytes != null) {
            existing.state = state
            existing.lastUpdatedAt = now
            existing.decisionGeneration++
            if (state in TERMINAL_STATES) {
                existing.decidedAt = now
                existing.decidedByMainDht = decidedByMainDht
            } else {
                existing.decidedAt = 0L
                existing.decidedByMainDht = ""
            }
            if (signedDecisionBytes != null) existing.signedDecisionBytes = signedDecisionBytes
            persist()
            logger("[groups-v2] BRANCH_DECISION_STORE event=${eventId.take(8)} branch=${branchId.take(12)} state=${state.name} generation=${existing.decisionGeneration}")
        }
        return existing
    }

    @Synchronized
    fun decisionsForGroup(groupId: String): List<GroupBranchDecisionRecordV2> =
        decisions.values.filter { it.groupId == groupId }.sortedBy { it.firstSeenAt }

    @Synchronized
    fun cleanup(now: Long = System.currentTimeMillis()): Int {
        val expired = events.values.filter { it.retainUntil <= now || it.eventExpiresAt in 1..now }.map { it.eventId }
        expired.forEach(events::remove)
        val expiredReceiptKeys = receipts.filterValues { it.retainUntil <= now }.keys.toList()
        expiredReceiptKeys.forEach(receipts::remove)
        val replayBefore = replay.size
        val cursorsBefore = cursors.size
        val decisionsBefore = decisions.size
        pruneReplay(now)
        pruneCursorHistory(now)
        pruneDecisionHistory(now)
        if (expired.isNotEmpty() || expiredReceiptKeys.isNotEmpty() || replay.size != replayBefore || cursors.size != cursorsBefore || decisions.size != decisionsBefore) persist()
        expired.forEach { logger("[groups-v2] CUSTODY_EVICT event=${it.take(8)} reason=expired") }
        return expired.size
    }

    @Synchronized
    fun diagnosticLines(groupName: (String) -> String? = { null }): List<String> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<String>()
        out += "custody events: ${events.size}  receipts: ${receipts.size}  cursors: ${cursors.size}  branch decisions: ${decisions.size}"
        events.values.groupBy { it.groupId }.toSortedMap().forEach { (groupId, rows) ->
            out += "${groupName(groupId) ?: groupId.take(8)} | custody=${rows.size} earliest-expiry=${rows.minOfOrNull { it.retainUntil }?.let { ((it - now).coerceAtLeast(0L) / 1000L) } ?: 0}s"
        }
        receipts.values.groupBy { it.eventId }.toSortedMap().entries.take(12).forEach { (eventId, rows) ->
            out += "receipt ${eventId.take(8)} | custodians=${rows.size} valid=${rows.count { it.verified }} latest-expiry=${rows.maxOfOrNull { it.retainUntil } ?: 0L}"
        }
        return out
    }

    private fun capacityRejectReason(groupId: String, authorMainDht: String): String? = when {
        events.values.count { it.groupId == groupId && it.authorMainDht == authorMainDht } >= MAX_PER_AUTHOR_PER_GROUP -> "author-cap"
        events.values.count { it.groupId == groupId } >= MAX_PER_GROUP -> "group-cap"
        events.size >= MAX_TOTAL_EVENTS -> "total-cap"
        else -> null
    }

    private fun pruneCursorHistory(now: Long) {
        val cutoff = now - CURSOR_RETENTION_MS
        cursors.entries.removeAll { (_, item) ->
            maxOf(item.lastRecoveryCompletedAt, item.lastRecoveryStartedAt) in 1 until cutoff
        }
        while (cursors.size > MAX_RECOVERY_CURSORS) {
            cursors.entries.minByOrNull { maxOf(it.value.lastRecoveryCompletedAt, it.value.lastRecoveryStartedAt) }?.key?.let(cursors::remove) ?: break
        }
    }

    private fun pruneDecisionHistory(now: Long) {
        val cutoff = now - DECISION_RETENTION_MS
        decisions.entries.removeAll { (_, item) -> item.state in TERMINAL_STATES && item.lastUpdatedAt < cutoff }
        while (decisions.size > MAX_BRANCH_DECISIONS) {
            val victim = decisions.values.filter { it.state in TERMINAL_STATES }.minByOrNull { it.lastUpdatedAt } ?: break
            decisions.remove(decisionKey(victim.groupId, victim.branchId, victim.eventId))
        }
    }

    private fun pruneReplay(now: Long) {
        replay.filterValues { it.expiresAt <= now }.keys.toList().forEach(replay::remove)
    }

    @Synchronized
    private fun load() {
        events.clear(); receipts.clear(); cursors.clear(); replay.clear(); decisions.clear()
        val raw = runCatching { vault.getText(STORE_KEY) }.getOrNull()
        if (raw.isNullOrBlank()) {
            logger("[groups-v2] CUSTODY_STORE_LOAD empty")
            return
        }
        runCatching {
            val root = JSONObject(raw)
            require(root.optInt("version") == STORE_VERSION) { "Unsupported Groups-v2 custody store version" }
            fun each(name: String, block: (JSONObject) -> Unit) {
                val a = root.optJSONArray(name) ?: JSONArray()
                repeat(a.length()) { a.optJSONObject(it)?.let(block) }
            }
            each("events") { CustodyEventRecordV2.fromJson(it).let { row -> events[row.eventId] = row } }
            each("receipts") { CustodyReceiptRecordV2.fromJson(it).let { row -> receipts[receiptKey(row.eventId, row.custodianMainDht)] = row } }
            each("cursors") { CustodyRecoveryCursorV2.fromJson(it).let { row -> cursors[cursorKey(row.groupId, row.branchId)] = row } }
            each("replay") { CustodyRequestReplayRecordV2.fromJson(it).let { row -> replay["${row.requesterMainDht}|${row.requestId}"] = row } }
            each("decisions") { GroupBranchDecisionRecordV2.fromJson(it).let { row -> decisions[decisionKey(row.groupId, row.branchId, row.eventId)] = row } }
            cleanup(System.currentTimeMillis())
            logger("[groups-v2] CUSTODY_STORE_LOAD events=${events.size} receipts=${receipts.size} cursors=${cursors.size} decisions=${decisions.size}")
        }.onFailure {
            events.clear(); receipts.clear(); cursors.clear(); replay.clear(); decisions.clear()
            logger("[groups-v2] CUSTODY_STORE_LOAD_FAILED ${it.message}")
        }
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) return
        val root = JSONObject()
            .put("version", STORE_VERSION)
            .put("events", JSONArray().apply { events.values.forEach { put(it.toJson()) } })
            .put("receipts", JSONArray().apply { receipts.values.forEach { put(it.toJson()) } })
            .put("cursors", JSONArray().apply { cursors.values.forEach { put(it.toJson()) } })
            .put("replay", JSONArray().apply { replay.values.forEach { put(it.toJson()) } })
            .put("decisions", JSONArray().apply { decisions.values.forEach { put(it.toJson()) } })
        vault.putText(STORE_KEY, root.toString(), PrivateVault.Retention.Persistent)
    }

    private fun receiptKey(eventId: String, custodian: String) = "$eventId|$custodian"
    private fun cursorKey(groupId: String, branchId: String) = "$groupId|$branchId"
    private fun decisionKey(groupId: String, branchId: String, eventId: String) = "$groupId|$branchId|$eventId"
    private fun short(value: String): String = if (value.length <= 18) value else "${value.take(9)}…${value.takeLast(6)}"

    companion object {
        private const val STORE_VERSION = 1
        private const val STORE_KEY = "groups_v2/custody/state_v1"
        private const val MAX_PER_GROUP = 1000
        private const val MAX_PER_AUTHOR_PER_GROUP = 100
        private const val MAX_TOTAL_EVENTS = 5000
        private const val MAX_RECEIPTS_PER_EVENT = 24
        private const val MAX_TOTAL_RECEIPTS = 5000
        private const val MAX_REPLAY_RECORDS = 2048
        private const val MAX_RECOVERY_CURSORS = 512
        private const val MAX_BRANCH_DECISIONS = 10_000
        private const val REPLAY_RETENTION_MS = 6L * 60L * 60L * 1000L
        private const val CURSOR_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private const val DECISION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private val TERMINAL_STATES = setOf(GroupBranchEventStateV2.Accepted, GroupBranchEventStateV2.Rejected, GroupBranchEventStateV2.Ignored)
    }
}
