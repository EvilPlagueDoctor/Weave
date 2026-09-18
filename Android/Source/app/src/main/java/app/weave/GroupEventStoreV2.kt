package app.weave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

const val GROUP_EVENT_RETENTION_MS_V2: Long = 7L * 24L * 60L * 60L * 1000L

enum class GroupEventTransportV2 {
    LocalCreated,
    DebugImport,
    Direct,
    PrivateMailbox,
    PublicWitness,
    CustodyGossip,
    CustodyRecovery,
    LegacyBridge,
}

enum class GroupEventValidationV2 {
    Unvalidated,
    Valid,
    InvalidFormat,
    InvalidSignature,
    UnsupportedVersion,
}

enum class GroupEventAuthorBindingV2 {
    LocalDaemonBound,
    RemoteUnbound,
    RemoteProfileBound,
}

enum class GroupEventContentStateV2 {
    Unchecked,
    Valid,
    HashMismatch,
    Unavailable,
}

/**
 * A same-event-id/different-body observation is not automatically author equivocation.
 * Only AuthenticatedEquivocation means both canonical bodies validate under the same signing key.
 */
enum class GroupEventConflictClassV2 {
    LegacyUnclassified,
    InvalidOrUnverified,
    DifferentSigner,
    AuthenticatedEquivocation,
}

enum class GroupBranchEventStateV2 { Unseen, Pending, Accepted, Rejected, Ignored }

data class GroupEventEvidenceV2(
    val transport: GroupEventTransportV2,
    var firstSeenAt: Long,
    var lastSeenAt: Long,
    var count: Int = 1,
    val sources: MutableSet<String> = linkedSetOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("transport", transport.name)
        .put("first_seen_at", firstSeenAt)
        .put("last_seen_at", lastSeenAt)
        .put("count", count)
        .put("sources", JSONArray(sources.toList().sorted()))

    companion object {
        fun fromJson(o: JSONObject): GroupEventEvidenceV2 {
            val sources = linkedSetOf<String>()
            val a = o.optJSONArray("sources") ?: JSONArray()
            repeat(a.length()) { a.optString(it).takeIf(String::isNotBlank)?.let(sources::add) }
            return GroupEventEvidenceV2(
                transport = GroupEventTransportV2.valueOf(o.getString("transport")),
                firstSeenAt = o.getLong("first_seen_at"),
                lastSeenAt = o.getLong("last_seen_at"),
                count = o.optInt("count", 1),
                sources = sources,
            )
        }
    }
}

data class GroupEventConflictV2(
    val receivedAt: Long,
    val canonicalDigestHex: String,
    val event: SignedGroupEventV2,
    val classification: GroupEventConflictClassV2 = GroupEventConflictClassV2.LegacyUnclassified,
    val validation: GroupEventValidationV2 = GroupEventValidationV2.Unvalidated,
    val transport: GroupEventTransportV2 = GroupEventTransportV2.LegacyBridge,
    val sourcePeer: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("received_at", receivedAt)
        .put("canonical_digest_hex", canonicalDigestHex)
        .put("event", event.toJson())
        .put("classification", classification.name)
        .put("validation", validation.name)
        .put("transport", transport.name)
        .put("source_peer", sourcePeer)

    companion object {
        fun fromJson(o: JSONObject): GroupEventConflictV2 = GroupEventConflictV2(
            receivedAt = o.getLong("received_at"),
            canonicalDigestHex = o.getString("canonical_digest_hex"),
            event = SignedGroupEventV2.fromJson(o.getJSONObject("event")),
            classification = runCatching { GroupEventConflictClassV2.valueOf(o.optString("classification")) }
                .getOrDefault(GroupEventConflictClassV2.LegacyUnclassified),
            validation = runCatching { GroupEventValidationV2.valueOf(o.optString("validation")) }
                .getOrDefault(GroupEventValidationV2.Unvalidated),
            transport = runCatching { GroupEventTransportV2.valueOf(o.optString("transport")) }
                .getOrDefault(GroupEventTransportV2.LegacyBridge),
            sourcePeer = o.optString("source_peer"),
        )
    }
}

data class StoredGroupEventV2(
    val event: SignedGroupEventV2,
    var firstSeenAt: Long,
    var lastSeenAt: Long,
    var validation: GroupEventValidationV2,
    var authorBinding: GroupEventAuthorBindingV2,
    var contentState: GroupEventContentStateV2 = GroupEventContentStateV2.Unchecked,
    var contentCheckedAt: Long = 0L,
    var contentRetryCount: Int = 0,
    var contentNextRetryAt: Long = 0L,
    val evidence: MutableMap<GroupEventTransportV2, GroupEventEvidenceV2> = linkedMapOf(),
    val branchStates: MutableMap<String, GroupBranchEventStateV2> = linkedMapOf(),
    val conflicts: MutableList<GroupEventConflictV2> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("event", event.toJson())
        .put("first_seen_at", firstSeenAt)
        .put("last_seen_at", lastSeenAt)
        .put("validation", validation.name)
        .put("author_binding", authorBinding.name)
        .put("content_state", contentState.name)
        .put("content_checked_at", contentCheckedAt)
        .put("content_retry_count", contentRetryCount)
        .put("content_next_retry_at", contentNextRetryAt)
        .put("evidence", JSONArray().apply { evidence.values.forEach { put(it.toJson()) } })
        .put("branch_states", JSONObject().apply {
            branchStates.toSortedMap().forEach { (branch, state) -> put(branch, state.name) }
        })
        .put("conflicts", JSONArray().apply { conflicts.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): StoredGroupEventV2 {
            val evidence = linkedMapOf<GroupEventTransportV2, GroupEventEvidenceV2>()
            val ea = o.optJSONArray("evidence") ?: JSONArray()
            repeat(ea.length()) {
                ea.optJSONObject(it)?.let(GroupEventEvidenceV2::fromJson)?.let { item ->
                    evidence[item.transport] = item
                }
            }
            val states = linkedMapOf<String, GroupBranchEventStateV2>()
            val so = o.optJSONObject("branch_states") ?: JSONObject()
            val keys = so.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { GroupBranchEventStateV2.valueOf(so.getString(key)) }
                    .getOrNull()?.let { states[key] = it }
            }
            val conflicts = mutableListOf<GroupEventConflictV2>()
            val ca = o.optJSONArray("conflicts") ?: JSONArray()
            repeat(ca.length()) { ca.optJSONObject(it)?.let { item -> conflicts += GroupEventConflictV2.fromJson(item) } }
            return StoredGroupEventV2(
                event = SignedGroupEventV2.fromJson(o.getJSONObject("event")),
                firstSeenAt = o.getLong("first_seen_at"),
                lastSeenAt = o.getLong("last_seen_at"),
                validation = runCatching { GroupEventValidationV2.valueOf(o.getString("validation")) }
                    .getOrDefault(GroupEventValidationV2.Unvalidated),
                authorBinding = runCatching { GroupEventAuthorBindingV2.valueOf(o.getString("author_binding")) }
                    .getOrDefault(GroupEventAuthorBindingV2.RemoteUnbound),
                contentState = runCatching { GroupEventContentStateV2.valueOf(o.optString("content_state")) }
                    .getOrDefault(GroupEventContentStateV2.Unchecked),
                contentCheckedAt = o.optLong("content_checked_at"),
                contentRetryCount = o.optInt("content_retry_count", 0),
                contentNextRetryAt = o.optLong("content_next_retry_at", 0L),
                evidence = evidence,
                branchStates = states,
                conflicts = conflicts,
            )
        }
    }
}

/**
 * Account-scoped, encrypted Groups-v2 event store.
 *
 * Every mutation is persisted synchronously through PrivateVault before returning. Weave therefore
 * does not need a graceful shutdown for event durability: once EVENT_STORE_INSERT/UPDATE is logged,
 * force-stopping the app must not lose that event.
 */
class GroupEventStoreV2(
    context: Context,
    private val logger: (String) -> Unit = {},
) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context.applicationContext)
    private val events = LinkedHashMap<String, StoredGroupEventV2>()

    @Volatile private var client: DaemonClient? = null
    @Volatile private var ownMainDht: String = ""
    @Volatile private var lastEventId: String? = null

    init { vault.register(this) }

    override fun onVaultAttached() = load()

    override fun onVaultDetached() = synchronized(this) {
        events.clear()
        lastEventId = null
        client = null
        ownMainDht = ""
    }

    @Synchronized
    fun bind(client: DaemonClient, ownMainDht: String) {
        this.client = client
        this.ownMainDht = ownMainDht
        // Loaded events may have been left Unvalidated because the vault attaches before the
        // controller has the authenticated daemon identity. Recheck them now.
        var changed = false
        val localSigningKey = runCatching { client.appSigningIdentity().publicKeyHex }.getOrNull()
        events.values.forEach { stored ->
            if (stored.validation == GroupEventValidationV2.Unvalidated) {
                stored.validation = verifySignature(stored.event)
                changed = true
            }
            if (stored.event.authorMainDht == ownMainDht &&
                localSigningKey != null &&
                stored.event.signingPublicKeyHex.equals(localSigningKey, true) &&
                stored.authorBinding != GroupEventAuthorBindingV2.LocalDaemonBound
            ) {
                stored.authorBinding = GroupEventAuthorBindingV2.LocalDaemonBound
                changed = true
            }
        }
        if (changed) persist()
        logger("[groups-v2] EVENT_STORE_BOUND events=${events.size} main=${short(ownMainDht)}")
    }

    @Synchronized
    fun unbind() {
        client = null
        ownMainDht = ""
    }

    @Synchronized
    fun createLocalEvent(
        eventType: GroupEventTypeV2,
        groupId: String,
        payload: GroupPayloadReferenceV2,
        contentHashHex: String,
        target: GroupEventTargetV2 = GroupEventTargetV2(),
        branchIdsForLocalState: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = createdAt + GROUP_EVENT_RETENTION_MS_V2,
    ): StoredGroupEventV2 {
        val daemon = client ?: error("Groups v2 event store is not bound to the daemon")
        val author = ownMainDht.ifBlank { error("Groups v2 author identity is unavailable") }
        val started = System.currentTimeMillis()
        val eventId = java.util.UUID.randomUUID().toString()
        logger("[groups-v2] EVENT_CREATE_BEGIN event=${shortEvent(eventId)} group=${short(groupId)} type=${eventType.name} author=${short(author)}")

        val signingIdentity = daemon.appSigningIdentity()
        require(signingIdentity.applicationId == DaemonClient.APP_ID) { "Unexpected app signing identity" }
        require(signingIdentity.mainDht == author) { "Signing identity is not bound to the active VeilKnit account" }

        val unsigned = SignedGroupEventV2(
            eventId = eventId,
            eventType = eventType,
            groupId = groupId,
            authorMainDht = author,
            createdAt = createdAt,
            expiresAt = expiresAt,
            target = target.normalized(),
            payload = payload,
            contentHashHex = contentHashHex.lowercase(),
            signingKeyGeneration = signingIdentity.keyGeneration,
            signingPublicKeyHex = signingIdentity.publicKeyHex.lowercase(),
            signatureHex = "",
        )
        val signStarted = System.currentTimeMillis()
        val signature = daemon.signAppPayload(SignedGroupEventV2.SIGNING_DOMAIN, unsigned.unsignedCanonicalBytes())
        require(signature.domain == SignedGroupEventV2.SIGNING_DOMAIN) { "Daemon signed the wrong event domain" }
        require(signature.keyGeneration == unsigned.signingKeyGeneration) { "Signing key changed during event creation" }
        require(signature.publicKeyHex.equals(unsigned.signingPublicKeyHex, ignoreCase = true)) { "Signing key changed during event creation" }
        val signed = unsigned.copy(signatureHex = signature.signatureHex.lowercase())
        logger("[groups-v2] EVENT_SIGNED event=${shortEvent(eventId)} key_gen=${signed.signingKeyGeneration} elapsed=${System.currentTimeMillis() - signStarted}ms")

        val stored = ingest(
            event = signed,
            transport = GroupEventTransportV2.LocalCreated,
            sourcePeer = author,
            locallyBound = true,
            branchIdsForLocalState = branchIdsForLocalState,
        )
        logger("[groups-v2] EVENT_CREATE_DONE event=${shortEvent(eventId)} validation=${stored.validation.name} digest=${signed.canonicalDigestHex().take(16)} elapsed=${System.currentTimeMillis() - started}ms")
        return stored
    }

    @Synchronized
    fun ingest(
        event: SignedGroupEventV2,
        transport: GroupEventTransportV2,
        sourcePeer: String = "",
        locallyBound: Boolean = false,
        branchIdsForLocalState: List<String> = emptyList(),
    ): StoredGroupEventV2 {
        val now = System.currentTimeMillis()
        logger("[groups-v2] EVENT_INGEST_BEGIN event=${shortEvent(event.eventId)} transport=${transport.name} source=${short(sourcePeer.ifBlank { "unknown" })}")
        event.structuralError(now)?.let { error("Structurally invalid Groups-v2 event: $it") }
        require(event.eventId.isNotBlank()) { "Group event has no event ID" }
        require(event.groupId.isNotBlank()) { "Group event has no group ID" }
        require(event.authorMainDht.isNotBlank()) { "Group event has no author" }
        require(event.payload.recordKey.isNotBlank()) { "Group event has no payload DHT" }
        require(event.payload.objectId.isNotBlank()) { "Group event has no payload object ID" }
        require(event.contentHashHex.matches(Regex("[0-9a-fA-F]{64}"))) { "Group event has an invalid content hash" }

        val incomingDigest = event.canonicalDigestHex()
        val existing = events[event.eventId]
        if (existing != null) {
            existing.lastSeenAt = now
            if (existing.event.canonicalDigestHex().equals(incomingDigest, ignoreCase = true) &&
                existing.event.signingPublicKeyHex.equals(event.signingPublicKeyHex, ignoreCase = true)
            ) {
                // Exact copies strengthen delivery evidence. If an older persisted record was left
                // unvalidated, take this opportunity to verify it now.
                if (existing.validation == GroupEventValidationV2.Unvalidated) {
                    existing.validation = verifySignature(event)
                }
                addEvidence(existing, transport, sourcePeer, now)
                branchIdsForLocalState.filter(String::isNotBlank).forEach {
                    existing.branchStates.putIfAbsent(it, GroupBranchEventStateV2.Pending)
                }
                persist()
                lastEventId = event.eventId
                logger("[groups-v2] EVENT_DUPLICATE event=${shortEvent(event.eventId)} canonical_match=true transport=${transport.name} count=${existing.evidence[transport]?.count ?: 1}")
                return existing
            }

            // A conflicting body must verify independently before it can be called equivocation.
            // Do NOT add its transport/source to the canonical event's evidence: a malicious relay
            // should not be able to manufacture evidence for the legitimate body it mutated.
            val incomingValidation = verifySignature(event)
            val sameClaimedAuthor = existing.event.authorMainDht == event.authorMainDht
            val sameSigningKey = existing.event.signingPublicKeyHex.equals(event.signingPublicKeyHex, true)
            val classification = when {
                incomingValidation != GroupEventValidationV2.Valid -> GroupEventConflictClassV2.InvalidOrUnverified
                existing.validation == GroupEventValidationV2.Valid && sameClaimedAuthor && sameSigningKey ->
                    GroupEventConflictClassV2.AuthenticatedEquivocation
                else -> GroupEventConflictClassV2.DifferentSigner
            }
            if (existing.conflicts.none { it.canonicalDigestHex.equals(incomingDigest, true) } &&
                existing.conflicts.size < MAX_CONFLICTS_PER_EVENT
            ) {
                existing.conflicts += GroupEventConflictV2(
                    receivedAt = now,
                    canonicalDigestHex = incomingDigest,
                    event = event,
                    classification = classification,
                    validation = incomingValidation,
                    transport = transport,
                    sourcePeer = sourcePeer,
                )
            }
            if (existing.conflicts.size >= MAX_CONFLICTS_PER_EVENT) {
                logger("[groups-v2] EVENT_CONFLICT_CAP event=${shortEvent(event.eventId)} max=$MAX_CONFLICTS_PER_EVENT")
            }
            persist()
            lastEventId = event.eventId
            val tag = if (classification == GroupEventConflictClassV2.AuthenticatedEquivocation) {
                "EVENT_CONFLICT_AUTHENTICATED"
            } else {
                "EVENT_CONFLICT_OBSERVED"
            }
            logger("[groups-v2] $tag event=${shortEvent(event.eventId)} class=${classification.name} validation=${incomingValidation.name} source=${short(sourcePeer.ifBlank { "unknown" })} existing=${existing.event.canonicalDigestHex().take(16)} incoming=${incomingDigest.take(16)} conflicts=${existing.conflicts.size}")
            return existing
        }

        val validation = when {
            event.protocolVersion != SignedGroupEventV2.PROTOCOL_VERSION -> GroupEventValidationV2.UnsupportedVersion
            else -> verifySignature(event)
        }
        val stored = StoredGroupEventV2(
            event = event,
            firstSeenAt = now,
            lastSeenAt = now,
            validation = validation,
            authorBinding = if (locallyBound) GroupEventAuthorBindingV2.LocalDaemonBound else GroupEventAuthorBindingV2.RemoteUnbound,
        )
        addEvidence(stored, transport, sourcePeer, now)
        branchIdsForLocalState.filter(String::isNotBlank).distinct().forEach {
            stored.branchStates[it] = GroupBranchEventStateV2.Pending
        }
        events[event.eventId] = stored
        lastEventId = event.eventId
        enforceCapacity(now)
        persist()
        logger("[groups-v2] EVENT_STORE_INSERT event=${shortEvent(event.eventId)} group=${short(event.groupId)} validation=${validation.name} binding=${stored.authorBinding.name} branches=${stored.branchStates.size} total=${events.size}")
        return stored
    }

    @Synchronized
    fun verifyContent(eventId: String, fetch: (WeaveObjectRef) -> WeaveMessage?): GroupEventContentStateV2 {
        val stored = events[eventId] ?: error("Unknown Groups v2 event")
        val started = System.currentTimeMillis()
        logger("[groups-v2] CONTENT_FETCH_BEGIN event=${shortEvent(eventId)} record=${short(stored.event.payload.recordKey)} subkey=${stored.event.payload.subkey}")
        val message = fetch(stored.event.payload.toWeaveRef())
        return verifyResolvedContent(stored, message, started)
    }

    /** Verify an already-fetched payload without causing a second DHT read. */
    @Synchronized
    fun verifyContentMessage(
        eventId: String,
        message: WeaveMessage?,
        startedAt: Long = System.currentTimeMillis(),
    ): GroupEventContentStateV2 {
        val stored = events[eventId] ?: error("Unknown Groups v2 event")
        return verifyResolvedContent(stored, message, startedAt)
    }

    private fun verifyResolvedContent(
        stored: StoredGroupEventV2,
        message: WeaveMessage?,
        started: Long,
    ): GroupEventContentStateV2 {
        val eventId = stored.event.eventId
        if (message == null) {
            markContentUnavailable(eventId, started, "not-found")
            return GroupEventContentStateV2.Unavailable
        }
        val actual = groupV2MessageHash(message)
        val state = if (actual.equals(stored.event.contentHashHex, ignoreCase = true)) {
            logger("[groups-v2] CONTENT_HASH_VALID event=${shortEvent(eventId)} hash=${actual.take(16)} elapsed=${System.currentTimeMillis() - started}ms")
            GroupEventContentStateV2.Valid
        } else {
            logger("[groups-v2] CONTENT_HASH_MISMATCH event=${shortEvent(eventId)} expected=${stored.event.contentHashHex.take(16)} actual=${actual.take(16)}")
            GroupEventContentStateV2.HashMismatch
        }
        stored.contentState = state
        stored.contentCheckedAt = System.currentTimeMillis()
        // A concrete result ends the transient retry chain. HashMismatch is terminal until a new
        // canonical event body arrives; Valid means moderation can continue immediately.
        stored.contentRetryCount = 0
        stored.contentNextRetryAt = 0L
        persist()
        return state
    }

    /**
     * Persist a transient DHT failure without losing a previously verified Valid state.
     * Returns the delay before the next retry.
     */
    @Synchronized
    fun markContentUnavailable(
        eventId: String,
        startedAt: Long = System.currentTimeMillis(),
        reason: String = "unavailable",
    ): Long {
        val stored = events[eventId] ?: return CONTENT_RETRY_BACKOFF_MS.first()
        val now = System.currentTimeMillis()
        if (stored.contentState != GroupEventContentStateV2.Valid) {
            stored.contentState = GroupEventContentStateV2.Unavailable
        }
        stored.contentCheckedAt = now
        stored.contentRetryCount = (stored.contentRetryCount + 1).coerceAtMost(1000)
        val delay = CONTENT_RETRY_BACKOFF_MS[minOf(stored.contentRetryCount - 1, CONTENT_RETRY_BACKOFF_MS.lastIndex)]
        stored.contentNextRetryAt = now + delay
        persist()
        logger("[groups-v2] CONTENT_UNAVAILABLE event=${shortEvent(eventId)} reason=${reason.take(64)} elapsed=${now - startedAt}ms retry=${stored.contentRetryCount} next_in=${delay}ms")
        return delay
    }

    @Synchronized
    fun markBranchState(eventId: String, branchId: String, state: GroupBranchEventStateV2) {
        val stored = events[eventId] ?: return
        val old = stored.branchStates.put(branchId, state)
        persist()
        logger("[groups-v2] BRANCH_STATE event=${shortEvent(eventId)} branch=${short(branchId)} ${old?.name ?: "none"}->${state.name}")
    }

    @Synchronized
    fun validateStandalone(event: SignedGroupEventV2): GroupEventValidationV2 = when {
        event.protocolVersion != SignedGroupEventV2.PROTOCOL_VERSION -> GroupEventValidationV2.UnsupportedVersion
        event.structuralError() != null -> GroupEventValidationV2.InvalidFormat
        else -> verifySignature(event)
    }

    @Synchronized fun get(eventId: String): StoredGroupEventV2? = events[eventId]
    @Synchronized fun allEvents(): List<StoredGroupEventV2> = events.values.toList()
    @Synchronized fun eventsForGroup(groupId: String): List<StoredGroupEventV2> = events.values.filter { it.event.groupId == groupId }
    @Synchronized fun size(): Int = events.size

    @Synchronized
    fun cleanup(now: Long = System.currentTimeMillis()): Int {
        val remove = events.values.filter { stored ->
            stored.event.expiresAt in 1 until now
        }.map { it.event.eventId }
        remove.forEach(events::remove)
        if (remove.isNotEmpty()) persist()
        logger("[groups-v2] EVENT_CLEANUP expired_removed=${remove.size} remaining=${events.size}")
        return remove.size
    }

    @Synchronized
    fun diagnosticLines(groupName: (String) -> String? = { null }): List<String> {
        val now = System.currentTimeMillis()
        val valid = events.values.count { it.validation == GroupEventValidationV2.Valid }
        val invalid = events.values.count { it.validation !in setOf(GroupEventValidationV2.Valid, GroupEventValidationV2.Unvalidated) }
        val pending = events.values.count { it.branchStates.values.any { state -> state == GroupBranchEventStateV2.Pending } }
        val conflicted = events.values.count { it.conflicts.isNotEmpty() }
        val authenticatedEquivocations = events.values.sumOf { stored ->
            stored.conflicts.count { it.classification == GroupEventConflictClassV2.AuthenticatedEquivocation }
        }
        val expired = events.values.count { it.event.expiresAt in 1 until now }
        val out = mutableListOf<String>()
        out += "protocol: ${SignedGroupEventV2.PROTOCOL_VERSION}"
        out += "stored events: ${events.size}  valid: $valid  invalid: $invalid  pending: $pending  expired: $expired  conflicted: $conflicted  authenticated-equivocations: $authenticatedEquivocations"
        events.values.groupBy { it.event.groupId }.toSortedMap().forEach { (groupId, items) ->
            val label = groupName(groupId)?.takeIf(String::isNotBlank) ?: short(groupId)
            val groupPending = items.count { it.branchStates.values.any { s -> s == GroupBranchEventStateV2.Pending } }
            out += "$label | events=${items.size} pending=$groupPending"
        }
        events.values.toList().takeLast(40).forEach { stored ->
            val conflictSummary = stored.conflicts.groupingBy { it.classification }.eachCount()
                .entries.joinToString(",") { "${it.key.name}:${it.value}" }
            val evidenceSummary = stored.evidence.values.joinToString(",") { "${it.transport.name}:${it.count}" }
            val retrySummary = if (stored.contentRetryCount > 0 || stored.contentNextRetryAt > 0L) {
                " | retry=${stored.contentRetryCount} next_in=${((stored.contentNextRetryAt - now).coerceAtLeast(0L) / 1000)}s"
            } else ""
            out += "event ${shortEvent(stored.event.eventId)} | group=${short(stored.event.groupId)} | ${stored.event.eventType.name} | validation=${stored.validation.name} | binding=${stored.authorBinding.name} | content=${stored.contentState.name}$retrySummary | evidence=$evidenceSummary | branches=${stored.branchStates.entries.joinToString(",") { "${short(it.key)}:${it.value.name}" }} | conflicts=${stored.conflicts.size}${if (conflictSummary.isNotBlank()) "[$conflictSummary]" else ""}"
        }
        return out
    }

    @Synchronized
    private fun verifySignature(event: SignedGroupEventV2): GroupEventValidationV2 {
        if (event.protocolVersion != SignedGroupEventV2.PROTOCOL_VERSION) return GroupEventValidationV2.UnsupportedVersion
        if (event.structuralError() != null) return GroupEventValidationV2.InvalidFormat
        if (event.signingPublicKeyHex.length != 64 || event.signatureHex.length != 128) return GroupEventValidationV2.InvalidFormat
        val daemon = client ?: return GroupEventValidationV2.Unvalidated
        val valid = runCatching {
            daemon.verifyAppSignature(
                publicKeyHex = event.signingPublicKeyHex,
                domain = SignedGroupEventV2.SIGNING_DOMAIN,
                payload = event.unsignedCanonicalBytes(),
                signatureHex = event.signatureHex,
            )
        }.getOrDefault(false)
        logger("[groups-v2] EVENT_SIGNATURE_${if (valid) "VALID" else "INVALID"} event=${shortEvent(event.eventId)} key=${event.signingPublicKeyHex.take(12)} key_gen=${event.signingKeyGeneration}")
        return if (valid) GroupEventValidationV2.Valid else GroupEventValidationV2.InvalidSignature
    }

    private fun addEvidence(stored: StoredGroupEventV2, transport: GroupEventTransportV2, sourcePeer: String, now: Long) {
        val item = stored.evidence[transport]
        if (item == null) {
            stored.evidence[transport] = GroupEventEvidenceV2(
                transport = transport,
                firstSeenAt = now,
                lastSeenAt = now,
                sources = linkedSetOf<String>().apply { sourcePeer.takeIf(String::isNotBlank)?.let(::add) },
            )
        } else {
            item.lastSeenAt = now
            item.count = (item.count + 1).coerceAtMost(MAX_EVIDENCE_COUNT)
            sourcePeer.takeIf(String::isNotBlank)?.let { source ->
                if (item.sources.size < MAX_EVIDENCE_SOURCES || source in item.sources) item.sources.add(source)
            }
        }
        stored.evidence[transport]?.let { item ->
            while (item.sources.size > MAX_EVIDENCE_SOURCES) item.sources.remove(item.sources.first())
        }
    }

    /** Keep the seven-day event vault bounded under high-volume or adversarial traffic. */
    private fun enforceCapacity(now: Long) {
        fun pending(row: StoredGroupEventV2) = row.branchStates.values.any { it == GroupBranchEventStateV2.Pending }
        fun evict(candidates: List<StoredGroupEventV2>, reason: String): Boolean {
            val victim = candidates.minWithOrNull(
                compareBy<StoredGroupEventV2> { pending(it) }
                    .thenBy { it.validation == GroupEventValidationV2.Valid }
                    .thenBy { it.lastSeenAt }
            ) ?: return false
            events.remove(victim.event.eventId)
            logger("[groups-v2] EVENT_EVICT event=${shortEvent(victim.event.eventId)} group=${short(victim.event.groupId)} reason=$reason pending=${pending(victim)}")
            return true
        }
        events.values.filter { it.event.expiresAt in 1 until now }.map { it.event.eventId }.forEach(events::remove)
        while (true) {
            val offender = events.values.groupBy { it.event.groupId to it.event.authorMainDht }
                .entries.firstOrNull { it.value.size > MAX_EVENTS_PER_AUTHOR_PER_GROUP } ?: break
            if (!evict(offender.value, "author-group-cap")) break
        }
        while (true) {
            val offender = events.values.groupBy { it.event.groupId }
                .entries.firstOrNull { it.value.size > MAX_EVENTS_PER_GROUP } ?: break
            if (!evict(offender.value, "group-cap")) break
        }
        while (events.size > MAX_TOTAL_EVENTS) {
            if (!evict(events.values.toList(), "total-cap")) break
        }
        if (lastEventId != null && lastEventId !in events) lastEventId = events.values.lastOrNull()?.event?.eventId
    }

    @Synchronized
    private fun load() {
        events.clear()
        val bytes = runCatching { vault.getValue(STORE_KEY) }.getOrNull()
        if (bytes == null) {
            logger("[groups-v2] EVENT_STORE_LOAD empty")
            return
        }
        runCatching {
            val root = JSONObject(bytes.decodeToString())
            require(root.optInt("version") == STORE_FORMAT_VERSION) { "Unsupported Groups v2 event-store version" }
            val array = root.optJSONArray("events") ?: JSONArray()
            repeat(array.length()) {
                val stored = StoredGroupEventV2.fromJson(array.getJSONObject(it))
                events[stored.event.eventId] = stored
            }
            lastEventId = root.optString("last_event_id").takeIf(events::containsKey)
            enforceCapacity(System.currentTimeMillis())
            logger("[groups-v2] EVENT_STORE_LOAD restored=${events.size} last=${lastEventId?.let { shortEvent(it) } ?: "none"}")
        }.onFailure { error ->
            events.clear()
            lastEventId = null
            logger("[groups-v2] EVENT_STORE_LOAD_FAILED ${error.message}")
        }
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) return
        val root = JSONObject()
            .put("version", STORE_FORMAT_VERSION)
            .put("last_event_id", lastEventId.orEmpty())
            .put("events", JSONArray().apply { events.values.forEach { put(it.toJson()) } })
        val bytes = root.toString().encodeToByteArray()
        vault.putValue(STORE_KEY, bytes, PrivateVault.Retention.Persistent)
        logger("[groups-v2] EVENT_STORE_COMMIT events=${events.size} bytes=${bytes.size}")
    }

    private fun shortEvent(value: String): String = value.substringBefore('-').take(12)

    companion object {
        private val CONTENT_RETRY_BACKOFF_MS = longArrayOf(15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)
        private const val STORE_FORMAT_VERSION = 1
        private const val STORE_KEY = "groups_v2/event_store_v1"
        private const val MAX_TOTAL_EVENTS = 4000
        private const val MAX_EVENTS_PER_GROUP = 1200
        private const val MAX_EVENTS_PER_AUTHOR_PER_GROUP = 300
        private const val MAX_CONFLICTS_PER_EVENT = 8
        private const val MAX_EVIDENCE_SOURCES = 16
        private const val MAX_EVIDENCE_COUNT = 1_000_000
    }
}
