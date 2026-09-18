package app.weave

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Creator/claimer branch persistence and transport.
 *
 * Branch DHT:
 *   subkey 0: GroupBranchHeader
 *   subkey 1: GroupPulse (current view only)
 *   remaining subkeys reserved for compact branch summaries.
 *
 * Durable events are stored in an append-only chain WITHOUT content refs.
 * Current moderation state lives in a hash-sharded overwriteable index, allowing Remove to
 * eliminate the active retrieval pointer while retaining the post hash/state.
 */
class GroupBranchNetwork(
    private val client: DaemonClient,
    private val store: GroupStore,
    private val logger: (String) -> Unit = {},
) {
    data class OwnedBranch(
        val storeId: String,
        val recordKey: String,
        val eventChain: CommentChain.Link,
        val indexStoreId: String,
        val indexRecordKey: String,
    )

    fun publishOriginal(group: GroupRecord, pulse: GroupPulse, ownerMainDht: String): Pair<GroupRecord, GroupBranchHeader> {
        require(group.ownerId == ownerMainDht) { "Only the creator can publish the original branch" }
        val branchId = originalBranchId(group.groupId)
        val owned = ensureOwnedBranch(group.groupId, branchId)
        val now = System.currentTimeMillis()
        val publicGroup = group.copy(
            rootRecordKey = owned.recordKey,
            updatedAt = now,
            pulseGeneration = pulse.generation,
        )
        val previous = readOwnHeader(owned.storeId)
        val header = GroupBranchHeader(
            group = publicGroup,
            branchId = branchId,
            kind = GroupBranchKind.Original,
            ownerMainDht = ownerMainDht,
            creatorRoot = owned.recordKey,
            branchRoot = owned.recordKey,
            eventRoot = owned.eventChain.recordKey,
            indexRoot = owned.indexRecordKey,
            generation = (previous?.generation ?: 0L) + 1L,
            featured = previous?.featured ?: publicGroup.featured,
            pinnedPostIds = previous?.pinnedPostIds.orEmpty(),
            moderators = previous?.moderators.orEmpty(),
            bannedMainDhts = previous?.bannedMainDhts.orEmpty(),
            knownBranches = mergeBranches(
                previous?.knownBranches.orEmpty(),
                GroupBranchPointer(
                    publicGroup.groupId, branchId, owned.recordKey, ownerMainDht,
                    GroupBranchKind.Original, owned.recordKey, now
                )
            ),
        )
        writeHeader(owned.storeId, header)
        writePulse(owned.storeId, pulse)
        if (previous == null) {
            appendEvent(
                owned,
                GroupEvent(
                    groupId = group.groupId,
                    branchId = branchId,
                    kind = GroupEventKind.GroupCreated,
                    actorMainDht = ownerMainDht,
                    createdAt = now,
                    objectId = group.groupId,
                    objectHash = groupSha256Hex(publicGroup.toPublicJson().toString().encodeToByteArray()),
                )
            )
        }
        store.rememberBranch(header.pointer(), selectIfNone = false, ownedByMe = true)
        store.upsertPulse(pulse, branchId)
        return publicGroup to header
    }

    /**
     * Claiming needs no creator permission. It is forbidden for member-only/encrypted groups.
     * The new branch is still anchored to the creatorRoot and retains the SAME groupId.
     */
    fun createClaim(group: GroupRecord, creatorHeader: GroupBranchHeader, claimerMainDht: String): GroupBranchHeader {
        require(group.policy.visibility != GroupVisibility.MembersOnly) {
            "Encrypted/member-only groups cannot be claimed"
        }
        require(creatorHeader.kind == GroupBranchKind.Original)
        require(creatorHeader.group.groupId == group.groupId)
        val branchId = UUID.randomUUID().toString()
        val owned = ensureOwnedBranch(group.groupId, branchId)
        val now = System.currentTimeMillis()
        val pointerToCreator = creatorHeader.pointer()
        val header = GroupBranchHeader(
            group = group.copy(rootRecordKey = creatorHeader.creatorRoot, role = GroupRole.Creator),
            branchId = branchId,
            kind = GroupBranchKind.Claim,
            ownerMainDht = claimerMainDht,
            creatorRoot = creatorHeader.creatorRoot,
            branchRoot = owned.recordKey,
            eventRoot = owned.eventChain.recordKey,
            indexRoot = owned.indexRecordKey,
            generation = 1,
            featured = creatorHeader.featured,
            pinnedPostIds = creatorHeader.pinnedPostIds,
            knownBranches = listOf(pointerToCreator),
        )
        writeHeader(owned.storeId, header)

        // A claim starts as a moderation copy of what the Original currently exposes, rather than
        // an empty group. From this point forward the claim is independent and may keep/remove
        // different items. The DHT refs are copied, not the media bytes themselves.
        val creatorPulse = readPulse(creatorHeader.branchRoot, force = true)
        val initialPulse = (creatorPulse ?: GroupPulse(group.groupId)).copy(
            generation = 1,
            updatedAt = now,
        )
        writePulse(owned.storeId, initialPulse)
        store.upsertPulse(initialPulse, branchId)

        appendEvent(
            owned,
            GroupEvent(
                groupId = group.groupId,
                branchId = branchId,
                kind = GroupEventKind.ClaimCreated,
                actorMainDht = claimerMainDht,
                createdAt = now,
                objectId = branchId,
                objectHash = groupSha256Hex(header.pointer().toJson().toString().encodeToByteArray()),
                metadata = mapOf("creator_root" to creatorHeader.creatorRoot),
            )
        )

        // Copy the Original's current visible index so posts/comments shown in the inherited Pulse
        // can actually be opened on the new branch. Failures are per-entry; one stale ref must not
        // prevent the claim itself from being created.
        readVisibleEntries(creatorHeader.branchRoot, maxEntries = 1000).forEach { entry ->
            val ref = entry.messageRef ?: return@forEach
            runCatching {
                applyDecision(
                    header = header,
                    postId = entry.postId,
                    conversationId = entry.conversationId,
                    postHash = entry.postHash,
                    state = GroupPostState.Visible,
                    messageRef = ref,
                    actorMainDht = claimerMainDht,
                    reason = "Inherited from Original when moderation was claimed",
                )
            }
        }

        store.rememberBranch(header.pointer(), selectIfNone = false, ownedByMe = true)
        return header
    }

    fun readHeader(branchRoot: String): GroupBranchHeader? = runCatching {
        val result = client.readPublicStore(branchRoot, listOf(HEADER_SUBKEY), true)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupBranchHeader.fromJson(JSONObject(bytes.decodeToString()))
    }.getOrNull()

    /**
     * Read the branch's current Pulse. The Pulse is the authoritative lightweight view of the
     * current post list, so remote reads must force a DHT refresh rather than accepting a cached
     * value indefinitely. This is particularly important for already-discovered groups: their
     * branch root usually stays the same while subkey 1 changes as posts/comments are accepted.
     */
    fun readPulse(branchRoot: String, force: Boolean = true): GroupPulse? = runCatching {
        val result = client.readPublicStore(branchRoot, listOf(PULSE_SUBKEY), force)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupPulse.fromJson(JSONObject(bytes.decodeToString()))
    }.getOrNull()

    fun readEvents(branchRoot: String, maxEntries: Int = 512): List<GroupEvent> {
        val header = readHeader(branchRoot) ?: return emptyList()
        return CommentChain.readAll(client, header.eventRoot, maxEntries)
            .mapNotNull(GroupEvent::fromBytes)
            .distinctBy { it.eventId }
    }

    /**
     * Current moderation lookup. One deterministic bucket is enough when the caller already knows
     * the post ID. A full conversation view can batch buckets later; the Group Pulse remains the
     * cheap initial view.
     */
    fun readVisibleEntries(
        branchRoot: String,
        conversationId: String? = null,
        maxEntries: Int = 100,
    ): List<GroupPostIndexEntry> = runCatching {
        val header = readHeader(branchRoot) ?: return emptyList()
        val wanted = (1 until INDEX_SUBKEYS).toList()
        val values = client.readPublicStore(header.indexRoot, wanted, true).optJSONArray("values")
            ?: return emptyList()
        val out = mutableListOf<GroupPostIndexEntry>()
        for (i in 0 until values.length()) {
            val bytes = CommentChain.decodeValue(values.optJSONObject(i)) ?: continue
            val entries = JSONObject(bytes.decodeToString()).optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j)?.let(GroupPostIndexEntry::fromJson) ?: continue
                if (entry.state != GroupPostState.Visible || entry.messageRef == null) continue
                if (conversationId != null && entry.conversationId != conversationId) continue
                out += entry
            }
        }
        out.distinctBy { it.postId }
            .sortedByDescending { it.decidedAt }
            .take(maxEntries.coerceIn(1, 500))
    }.getOrElse { emptyList() }

    fun readPostDecision(branchRoot: String, postId: String): GroupPostIndexEntry? = runCatching {
        val header = readHeader(branchRoot) ?: return null
        val bucket = bucketFor(postId)
        val result = client.readPublicStore(header.indexRoot, listOf(bucket), true)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        val arr = JSONObject(bytes.decodeToString()).optJSONArray("entries") ?: return null
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i)?.let(GroupPostIndexEntry::fromJson) ?: continue
            if (entry.postId == postId) return entry
        }
        null
    }.getOrNull()

    /**
     * Owner applies one branch decision. Every decision appends a hash-only journal event, then
     * overwrites the current bucket. Removed/Pending entries physically omit the message ref.
     */
    fun applyDecision(
        header: GroupBranchHeader,
        postId: String,
        conversationId: String,
        postHash: String,
        state: GroupPostState,
        messageRef: WeaveObjectRef?,
        actorMainDht: String,
        reason: String = "",
    ): GroupPostIndexEntry {
        require(header.ownerMainDht == client.identity().optString("main_dht")) {
            "Only this branch owner may write its branch index"
        }
        val grant = header.moderators.firstOrNull { it.moderatorMainDht == actorMainDht }
        require(actorMainDht == header.ownerMainDht || grant?.canModeratePosts == true) {
            "Actor is not authorized to moderate posts on this branch"
        }
        val ref = if (state == GroupPostState.Visible) {
            requireNotNull(messageRef) { "Visible posts require a message ref" }
        } else null
        val entry = GroupPostIndexEntry(
            postId = postId,
            conversationId = conversationId,
            postHash = postHash,
            state = state,
            messageRef = ref,
            decidedBy = actorMainDht,
            decidedAt = System.currentTimeMillis(),
            reason = reason,
        )
        val owned = resolveOwned(header)
        writeIndexEntry(owned.indexStoreId, entry)
        val kind = when (state) {
            GroupPostState.Pending -> GroupEventKind.PostSubmitted
            GroupPostState.Visible -> GroupEventKind.PostAllowed
            GroupPostState.Removed -> GroupEventKind.PostRemoved
        }
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = kind,
                actorMainDht = actorMainDht,
                createdAt = entry.decidedAt,
                objectId = postId,
                conversationId = conversationId,
                objectHash = postHash,
                reason = reason,
            )
        )
        return entry
    }

    fun restorePost(
        header: GroupBranchHeader,
        postId: String,
        conversationId: String,
        postHash: String,
        messageRef: WeaveObjectRef,
        actorMainDht: String,
        reason: String = "",
    ): GroupPostIndexEntry {
        val entry = applyDecision(
            header, postId, conversationId, postHash, GroupPostState.Visible,
            messageRef, actorMainDht, reason
        )
        val owned = resolveOwned(header)
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = GroupEventKind.PostRestored,
                actorMainDht = actorMainDht,
                createdAt = System.currentTimeMillis(),
                objectId = postId,
                conversationId = conversationId,
                objectHash = postHash,
                reason = reason,
            )
        )
        return entry
    }

    fun publishPulse(header: GroupBranchHeader, pulse: GroupPulse) {
        require(header.ownerMainDht == client.identity().optString("main_dht")) {
            "Only this branch owner may publish its Pulse"
        }
        val owned = resolveOwned(header)
        writePulse(owned.storeId, pulse)
        store.upsertPulse(pulse, header.branchId)
    }

    fun setFeatured(
        header: GroupBranchHeader,
        slot: FeaturedSlot,
        actorMainDht: String,
    ): GroupBranchHeader {
        require(header.ownerMainDht == client.identity().optString("main_dht"))
        val grant = header.moderators.firstOrNull { it.moderatorMainDht == actorMainDht }
        require(actorMainDht == header.ownerMainDht || grant?.canEditFeatured == true) {
            "Actor may not edit the featured area on this branch"
        }
        val updated = header.copy(
            generation = header.generation + 1,
            featured = slot,
        )
        val owned = resolveOwned(header)
        writeHeader(owned.storeId, updated)
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = GroupEventKind.FeaturedChanged,
                actorMainDht = actorMainDht,
                createdAt = System.currentTimeMillis(),
                objectId = slot.ref?.objectId.orEmpty(),
                objectHash = groupSha256Hex(slot.toJson().toString().encodeToByteArray()),
            )
        )
        store.rememberBranchHeader(updated)
        return updated
    }

    fun setPinned(
        header: GroupBranchHeader,
        postId: String,
        pinned: Boolean,
        actorMainDht: String,
    ): GroupBranchHeader {
        require(header.ownerMainDht == client.identity().optString("main_dht"))
        val grant = header.moderators.firstOrNull { it.moderatorMainDht == actorMainDht }
        require(actorMainDht == header.ownerMainDht || grant?.canPinPosts == true) {
            "Actor may not pin posts on this branch"
        }
        val updatedHeader = header.copy(
            generation = header.generation + 1,
            pinnedPostIds = if (pinned) {
                (header.pinnedPostIds.filterNot { it == postId } + postId).takeLast(100)
            } else {
                header.pinnedPostIds.filterNot { it == postId }
            },
        )
        val owned = resolveOwned(header)
        writeHeader(owned.storeId, updatedHeader)
        store.rememberBranchHeader(updatedHeader)

        val current = readPulse(header.branchRoot) ?: GroupPulse(header.group.groupId)
        val updatedConversations = current.conversations.map { conversation ->
            conversation.copy(
                recentMessages = conversation.recentMessages
                    .map { if (it.messageId == postId) it.copy(pinned = pinned) else it }
                    .sortedWith(compareByDescending<MessagePreview> { it.pinned }.thenByDescending { it.createdAt })
            )
        }
        val updated = current.copy(
            generation = current.generation + 1,
            updatedAt = System.currentTimeMillis(),
            conversations = updatedConversations,
        )
        publishPulse(updatedHeader, updated)
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = if (pinned) GroupEventKind.PostPinned else GroupEventKind.PostUnpinned,
                actorMainDht = actorMainDht,
                createdAt = System.currentTimeMillis(),
                objectId = postId,
            )
        )
        return updatedHeader
    }

    fun grantModerator(header: GroupBranchHeader, grant: GroupModeratorGrant): GroupBranchHeader {
        require(header.ownerMainDht == client.identity().optString("main_dht"))
        val updated = header.copy(
            generation = header.generation + 1,
            moderators = (header.moderators.filterNot { it.moderatorMainDht == grant.moderatorMainDht } + grant)
                .take(64),
        )
        val owned = resolveOwned(header)
        writeHeader(owned.storeId, updated)
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = GroupEventKind.ModeratorGranted,
                actorMainDht = header.ownerMainDht,
                createdAt = System.currentTimeMillis(),
                objectId = grant.moderatorMainDht,
            )
        )
        return updated
    }

    fun banAuthor(
        header: GroupBranchHeader,
        authorMainDht: String,
        actorMainDht: String,
    ): GroupBranchHeader {
        require(header.ownerMainDht == client.identity().optString("main_dht"))
        val grant = header.moderators.firstOrNull { it.moderatorMainDht == actorMainDht }
        require(actorMainDht == header.ownerMainDht || grant?.canModeratePosts == true) {
            "Actor may not ban authors on this branch"
        }
        require(authorMainDht.isNotBlank()) { "Author identity is empty" }
        val updated = header.copy(
            generation = header.generation + 1,
            bannedMainDhts = (header.bannedMainDhts.filterNot { it == authorMainDht } + authorMainDht)
                .takeLast(512),
        )
        val owned = resolveOwned(header)
        writeHeader(owned.storeId, updated)
        appendEvent(
            owned,
            GroupEvent(
                groupId = header.group.groupId,
                branchId = header.branchId,
                kind = GroupEventKind.MemberBanned,
                actorMainDht = actorMainDht,
                createdAt = System.currentTimeMillis(),
                objectId = authorMainDht,
            )
        )
        store.rememberBranchHeader(updated)
        return updated
    }

    fun observeBranch(header: GroupBranchHeader, pointer: GroupBranchPointer): GroupBranchHeader {
        if (pointer.groupId != header.group.groupId || pointer.creatorRoot != header.creatorRoot) return header
        val updated = header.copy(
            generation = header.generation + 1,
            knownBranches = mergeBranches(header.knownBranches, pointer),
        )
        if (header.ownerMainDht == client.identity().optString("main_dht")) {
            val owned = resolveOwned(header)
            writeHeader(owned.storeId, updated)
            appendEvent(
                owned,
                GroupEvent(
                    groupId = header.group.groupId,
                    branchId = header.branchId,
                    kind = GroupEventKind.BranchObserved,
                    actorMainDht = header.ownerMainDht,
                    createdAt = System.currentTimeMillis(),
                    objectId = pointer.branchId,
                    objectHash = groupSha256Hex(pointer.toJson().toString().encodeToByteArray()),
                )
            )
        }
        store.rememberBranch(pointer, selectIfNone = false)
        return updated
    }

    /**
     * Record something this authority learned from direct delivery, mailbox, a spectator request,
     * or another authority. The durable copy is branch-specific and hash-only.
     */
    fun recordObservedEvent(header: GroupBranchHeader, source: GroupEvent) {
        require(header.ownerMainDht == client.identity().optString("main_dht")) {
            "Only this branch owner may append its observed-event journal"
        }
        val owned = resolveOwned(header)
        // Preserve the event's originating branch. A journal is "what this authority has seen",
        // not a claim that the local branch made the same moderation decision.
        appendEvent(owned, source)
    }

    /**
     * Imports missing hash/action events from another creator/claimer journal. This is the slow
     * catch-up path for an authority that was offline; live traffic still arrives by mailbox /
     * ServiceRequest. Content refs are intentionally absent from journals.
     */
    fun reconcileObservedEvents(localHeader: GroupBranchHeader, remoteEvents: List<GroupEvent>): Int {
        require(localHeader.ownerMainDht == client.identity().optString("main_dht")) {
            "Only this branch owner may reconcile its journal"
        }
        val localIds = readEvents(localHeader.branchRoot, 2048).mapTo(mutableSetOf()) { it.eventId }
        val owned = resolveOwned(localHeader)
        var added = 0
        remoteEvents
            .asSequence()
            .filter { it.groupId == localHeader.group.groupId }
            .filter { it.eventId !in localIds }
            .take(512)
            .forEach { event ->
                appendEvent(owned, event)
                localIds += event.eventId
                added++
            }
        return added
    }

    /**
     * Normal open-group post/conversation submission. Publish the same event toward up to three
     * known authorities. Each ServiceRequest is spectator-readable, so if every moderator is
     * offline other users can still inspect recent UNREVIEWED activity until the TTL expires.
     */
    fun publishOpenIntake(
        envelope: GroupWireEnvelope,
        authorities: List<GroupBranchPointer>,
        ttlSeconds: Long = OPEN_INTAKE_TTL_SECONDS,
    ): List<String> {
        require(envelope.toBytes().size <= 8 * 1024) { "Group intake envelope exceeds daemon message limit" }
        return authorities
            .filter { it.ownerMainDht.isNotBlank() }
            .distinctBy { it.ownerMainDht }
            .take(3)
            .mapNotNull { authority ->
                runCatching {
                    val result = client.publishServiceRequest(
                        intendedHostMainDht = authority.ownerMainDht,
                        serviceIdHex = OPEN_INTAKE_SERVICE_ID,
                        manifestHashHex = OPEN_INTAKE_MANIFEST_HASH,
                        instanceIdHex = instanceId(envelope.event.groupId, authority.branchId),
                        payload = envelope.copy(targetBranchOwner = authority.ownerMainDht).toBytes(),
                        delegationAllowed = true,
                        spectatorsAllowed = true,
                        ttlSeconds = ttlSeconds,
                    )
                    result.optString("request_id_hex").takeIf { it.isNotBlank() }
                }.getOrNull()
            }
    }

    /**
     * Groups-v2 submission transport. The signed event is dispatched through three independent
     * paths: a direct-preferred authenticated send, a forced persistent mailbox copy, and a
     * spectator-readable public witness. All operations are queued on a bounded process-wide pool
     * so posting does not wait for slow DHT/mailbox commits.
     */
    fun sendCanonicalSubmissionV2(
        event: SignedGroupEventV2,
        authorities: List<GroupBranchPointer>,
        privateIntakeKey: ByteArray? = null,
    ) {
        val selectedBranch = store.selectedBranch(event.groupId)?.branchId
        val targets = authorities
            .filter { it.ownerMainDht.isNotBlank() }
            .distinctBy { it.ownerMainDht }
            .sortedWith(
                compareByDescending<GroupBranchPointer> { it.branchId == selectedBranch }
                    .thenByDescending { it.kind == GroupBranchKind.Original }
                    .thenByDescending { it.updatedAt }
            )
            .take(MAX_V2_AUTHORITY_FANOUT)

        val remoteTargets = targets.filter { it.ownerMainDht != event.authorMainDht }
        val mailboxExpirySeconds = (event.expiresAt / 1000L).takeIf { it > 0L }
        logger("[groups-v2] TRANSPORT_SCHEDULE event=${event.eventId.take(8)} authorities=${targets.size} remote=${remoteTargets.size} fast=${remoteTargets.size} mailbox=${remoteTargets.size} witness=${targets.take(PUBLIC_WITNESS_HOSTS).size} private=${privateIntakeKey != null}")

        remoteTargets.forEach { authority ->
            val packet = GroupEventTransportPacketV2(event, targetOwnerMainDht = authority.ownerMainDht)
            val bytes = packet.toBytes()
            require(bytes.size <= 8 * 1024) { "Groups v2 direct packet exceeds daemon message limit" }

            scheduleV2Transport("FAST_PATH", event.eventId, authority.ownerMainDht) {
                client.sendMessage(
                    recipientMainDht = authority.ownerMainDht,
                    payload = bytes,
                    expiresAtSeconds = mailboxExpirySeconds,
                    preferDirect = true,
                ).optString("message_id_hex")
            }
            scheduleV2Transport("MAILBOX_COPY", event.eventId, authority.ownerMainDht) {
                client.sendMessage(
                    recipientMainDht = authority.ownerMainDht,
                    payload = bytes,
                    expiresAtSeconds = mailboxExpirySeconds,
                    preferDirect = false,
                ).optString("message_id_hex")
            }
        }

        // A ServiceRequest is spectator-readable, so a few host copies are enough to make the
        // same group-scoped event independently observable without publishing once per Claim.
        // The witness uses a compact binary codec because the daemon ServiceRequest wrapper has a
        // strict 1024-byte serialized ceiling; direct/mailbox packets intentionally remain JSON.
        val witnessPacket = GroupEventTransportPacketV2(event)
        val compactWitness = GroupEventWitnessCodecV2.encode(event)
        val witnessPayload = privateIntakeKey?.let { GroupEventTransportCryptoV2.encrypt(witnessPacket, it) }
            ?: compactWitness
        require(witnessPayload.size <= MAX_V2_WITNESS_PAYLOAD_BYTES) {
            "Groups v2 compact witness is unexpectedly large: ${witnessPayload.size} bytes"
        }
        logger("[groups-v2] WITNESS_ENCODE event=${event.eventId.take(8)} compact_bytes=${compactWitness.size} payload_bytes=${witnessPayload.size} encrypted=${privateIntakeKey != null}")
        targets.take(PUBLIC_WITNESS_HOSTS).forEach { host ->
            scheduleV2Transport("PUBLIC_WITNESS", event.eventId, host.ownerMainDht) {
                val result = client.publishServiceRequest(
                    intendedHostMainDht = host.ownerMainDht,
                    serviceIdHex = if (privateIntakeKey == null) V2_PUBLIC_WITNESS_SERVICE_ID else V2_PRIVATE_WITNESS_SERVICE_ID,
                    manifestHashHex = if (privateIntakeKey == null) V2_PUBLIC_WITNESS_MANIFEST_HASH else V2_PRIVATE_WITNESS_MANIFEST_HASH,
                    instanceIdHex = v2WitnessInstanceId(event.groupId, host.ownerMainDht),
                    payload = witnessPayload,
                    delegationAllowed = true,
                    spectatorsAllowed = true,
                    ttlSeconds = OPEN_INTAKE_TTL_SECONDS,
                )
                result.optString("request_id_hex")
            }
        }
    }

    private fun scheduleV2Transport(
        label: String,
        eventId: String,
        target: String,
        operation: () -> String,
    ) {
        V2_TRANSPORT_POOL.execute {
            val started = System.currentTimeMillis()
            runCatching(operation)
                .onSuccess { receipt ->
                    logger("[groups-v2] TRANSPORT_${label}_QUEUED event=${eventId.take(8)} target=${short(target)} receipt=${receipt.take(16)} elapsed=${System.currentTimeMillis() - started}ms")
                }
                .onFailure { error ->
                    val message = error.message.orEmpty()
                    logger("[groups-v2] TRANSPORT_${label}_FAILED event=${eventId.take(8)} target=${short(target)} elapsed=${System.currentTimeMillis() - started}ms error=${message.take(240)}")
                    if (message.contains("reputation", ignoreCase = true) || message.contains("blocked", ignoreCase = true)) {
                        logger("[groups-v2] REPUTATION_BLOCK_HINT event=${eventId.take(8)} target=${short(target)} transport=$label note=daemon_policy_rejected_send")
                    }
                }
        }
    }

    /**
     * Member-only groups use the same spectator-readable mailbox primitive, but the entire event
     * envelope is AES-GCM ciphertext. Other group members with the intake key can still inspect
     * pending activity while the creator is offline; outsiders see only opaque bytes.
     */
    fun publishEncryptedPrivateIntake(
        envelope: GroupWireEnvelope,
        creator: GroupBranchPointer,
        intakeKey: ByteArray,
        ttlSeconds: Long = OPEN_INTAKE_TTL_SECONDS,
    ): String? = runCatching {
        val encrypted = GroupIntakeCrypto.encrypt(
            envelope.copy(targetBranchOwner = creator.ownerMainDht),
            intakeKey,
        )
        require(encrypted.size <= 8 * 1024) { "Encrypted group intake exceeds daemon message limit" }
        val result = client.publishServiceRequest(
            intendedHostMainDht = creator.ownerMainDht,
            serviceIdHex = PRIVATE_INTAKE_SERVICE_ID,
            manifestHashHex = PRIVATE_INTAKE_MANIFEST_HASH,
            instanceIdHex = privateInstanceId(intakeKey),
            payload = encrypted,
            delegationAllowed = true,
            spectatorsAllowed = true,
            ttlSeconds = ttlSeconds,
        )
        result.optString("request_id_hex").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Reports, join requests, moderator actions, branch reconciliation and sensitive authority
     * traffic use authenticated end-to-end application messaging/mailbox delivery.
     */
    fun sendPrivateAuthorityEvent(envelope: GroupWireEnvelope, authorities: List<GroupBranchPointer>) {
        val bytes = envelope.toBytes()
        require(bytes.size <= 8 * 1024) { "Group authority envelope exceeds daemon message limit" }
        authorities
            .filter { it.ownerMainDht.isNotBlank() }
            .distinctBy { it.ownerMainDht }
            .take(6)
            .forEach { authority ->
                client.sendMessage(
                    recipientMainDht = authority.ownerMainDht,
                    payload = envelope.copy(targetBranchOwner = authority.ownerMainDht).toBytes(),
                )
            }
    }

    /**
     * Called for authenticated direct/mailbox messages. sourceMainDht is supplied by the daemon,
     * not trusted from the payload.
     */
    fun receivePrivate(envelope: GroupWireEnvelope, sourceMainDht: String) {
        val e = envelope.event
        when (e.kind) {
            GroupEventKind.ReportSubmitted -> {
                store.rememberPendingEnvelope(e, envelope.contentRef, sourceMainDht)
                store.addModerationTask(
                GroupModerationTask(
                    taskId = e.eventId,
                    groupId = e.groupId,
                    kind = GroupModerationKind.Report,
                    objectRef = envelope.contentRef,
                    reporterId = sourceMainDht,
                    reason = e.reason,
                    createdAt = e.createdAt,
                )
                )
            }
            GroupEventKind.JoinRequested -> {
                store.rememberPendingEnvelope(e, envelope.contentRef, sourceMainDht)
                store.addModerationTask(
                GroupModerationTask(
                    taskId = e.eventId,
                    groupId = e.groupId,
                    kind = GroupModerationKind.JoinRequest,
                    reporterId = sourceMainDht,
                    reason = e.reason,
                    createdAt = e.createdAt,
                )
                )
            }
            else -> store.rememberPendingEnvelope(e, envelope.contentRef, sourceMainDht)
        }
    }

    /**
     * Called for spectator-readable ServiceRequests. This is ONLY a pending preview; it is never
     * interpreted as a branch approval. The branch's current index wins as soon as it records a
     * Visible/Removed state.
     */
    fun receiveOpenServiceRequest(event: JSONObject) {
        val payload = runCatching {
            Base64.decode(event.getString("payload_base64"), Base64.DEFAULT)
        }.getOrNull() ?: return
        val envelope = GroupWireEnvelope.fromBytes(payload) ?: return
        store.rememberPublicPending(envelope.event, envelope.contentRef, event.optLong("expires_at"))
    }

    fun subscribeOpenIntake(onClosed: (String) -> Unit = {}): Long =
        client.subscribeServiceRequests(
            serviceIdsHex = listOf(
                OPEN_INTAKE_SERVICE_ID, PRIVATE_INTAKE_SERVICE_ID,
                V2_PUBLIC_WITNESS_SERVICE_ID, V2_PRIVATE_WITNESS_SERVICE_ID,
            ),
            onRequest = ::receiveOpenServiceRequest,
            onClosed = onClosed,
        )

    private fun ensureOwnedBranch(groupId: String, branchId: String): OwnedBranch {
        val safeGroup = safe(groupId)
        val safeBranch = safe(branchId)
        val branchStore = findOrCreate("weave_group_${safeGroup}_${safeBranch}_branch", BRANCH_SUBKEYS)
        val events = CommentChain.ensureOwnedStore(client, "weave_group_${safeGroup}_${safeBranch}_events")
        val index = findOrCreate("weave_group_${safeGroup}_${safeBranch}_index", INDEX_SUBKEYS)
        return OwnedBranch(
            storeId = branchStore.first,
            recordKey = branchStore.second,
            eventChain = events,
            indexStoreId = index.first,
            indexRecordKey = index.second,
        )
    }

    private fun resolveOwned(header: GroupBranchHeader): OwnedBranch {
        val owned = ensureOwnedBranch(header.group.groupId, header.branchId)
        require(owned.recordKey == header.branchRoot) { "Branch root does not belong to this device" }
        return owned
    }

    private fun findOrCreate(name: String, subkeys: Int): Pair<String, String> {
        val stores = client.listStores().optJSONArray("stores")
        if (stores != null) for (i in 0 until stores.length()) {
            val s = stores.getJSONObject(i)
            if (s.optString("name") == name) return s.getString("store_id") to s.getString("record_key")
        }
        val s = client.createStore(name, subkeys).getJSONObject("store")
        return s.getString("store_id") to s.getString("record_key")
    }

    private fun writeHeader(storeId: String, header: GroupBranchHeader) {
        client.writeStore(storeId, HEADER_SUBKEY, header.toJson().toString().encodeToByteArray())
    }

    private fun readOwnHeader(storeId: String): GroupBranchHeader? = runCatching {
        val values = client.readStore(storeId, listOf(HEADER_SUBKEY), false).optJSONArray("values")
        val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return null
        GroupBranchHeader.fromJson(JSONObject(bytes.decodeToString()))
    }.getOrNull()

    private fun writePulse(storeId: String, pulse: GroupPulse) {
        client.writeStore(storeId, PULSE_SUBKEY, pulse.toJson().toString().encodeToByteArray())
    }

    private fun appendEvent(owned: OwnedBranch, event: GroupEvent) {
        CommentChain.append(
            client,
            owned.eventChain,
            "weave_group_${safe(event.groupId)}_${safe(event.branchId)}_events_next",
            event.toBytes(),
        )
        store.markEventSeen(event.eventId)
    }

    private fun writeIndexEntry(indexStoreId: String, entry: GroupPostIndexEntry) {
        val bucket = bucketFor(entry.postId)
        val current: List<GroupPostIndexEntry> = runCatching {
            val values = client.readStore(indexStoreId, listOf(bucket), false).optJSONArray("values")
            val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return@runCatching emptyList()
            val a = JSONObject(bytes.decodeToString()).optJSONArray("entries") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let(GroupPostIndexEntry::fromJson)?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
        val next = (current.filterNot { it.postId == entry.postId } + entry).takeLast(MAX_INDEX_BUCKET_ENTRIES)
        val payload = JSONObject()
            .put("v", 2)
            .put("entries", JSONArray().apply { next.forEach { put(it.toJson()) } })
            .toString()
            .encodeToByteArray()
        client.writeStore(indexStoreId, bucket, payload)
    }

    companion object {
        const val BRANCH_SUBKEYS = 8
        const val HEADER_SUBKEY = 0
        const val PULSE_SUBKEY = 1

        const val INDEX_SUBKEYS = 64
        const val MAX_INDEX_BUCKET_ENTRIES = 128

        const val OPEN_INTAKE_TTL_SECONDS = 60L * 60L
        const val MAX_V2_AUTHORITY_FANOUT = 24
        const val PUBLIC_WITNESS_HOSTS = 3
        const val MAX_V2_WITNESS_PAYLOAD_BYTES = 700

        private val V2_TRANSPORT_POOL = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "weave-groups-v2-transport").apply { isDaemon = true }
        }

        // 32-byte SHA-256 identifiers encoded as hex; stable across every Weave install.
        val OPEN_INTAKE_SERVICE_ID: String = groupSha256Hex("weave/group/open-intake/v2".encodeToByteArray())
        val OPEN_INTAKE_MANIFEST_HASH: String = groupSha256Hex("weave/group/open-intake/manifest/v2".encodeToByteArray())
        val PRIVATE_INTAKE_SERVICE_ID: String = groupSha256Hex("weave/group/private-intake/v2".encodeToByteArray())
        val PRIVATE_INTAKE_MANIFEST_HASH: String = groupSha256Hex("weave/group/private-intake/manifest/v2".encodeToByteArray())
        val V2_PUBLIC_WITNESS_SERVICE_ID: String = groupSha256Hex("weave/group/event-witness/v2".encodeToByteArray())
        val V2_PUBLIC_WITNESS_MANIFEST_HASH: String = groupSha256Hex("weave/group/event-witness/manifest/v2".encodeToByteArray())
        val V2_PRIVATE_WITNESS_SERVICE_ID: String = groupSha256Hex("weave/group/event-witness-private/v2".encodeToByteArray())
        val V2_PRIVATE_WITNESS_MANIFEST_HASH: String = groupSha256Hex("weave/group/event-witness-private/manifest/v2".encodeToByteArray())

        fun originalBranchId(groupId: String): String =
            "original-${groupSha256Hex(groupId.encodeToByteArray()).take(24)}"

        private fun instanceId(groupId: String, branchId: String): String =
            groupSha256Hex("$groupId|$branchId".encodeToByteArray())

        private fun privateInstanceId(key: ByteArray): String =
            groupSha256Hex(key + "|weave-private-intake|".encodeToByteArray())

        private fun v2WitnessInstanceId(groupId: String, hostMainDht: String): String =
            groupSha256Hex("$groupId|$hostMainDht|weave-v2-witness".encodeToByteArray())

        private fun bucketFor(postId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(postId.encodeToByteArray())
            return ((digest[0].toInt() and 0xff) % (INDEX_SUBKEYS - 1)) + 1
        }

        private fun safe(value: String): String =
            value.replace(Regex("[^A-Za-z0-9]"), "").take(28)

        private fun mergeBranches(
            existing: List<GroupBranchPointer>,
            extra: GroupBranchPointer,
        ): List<GroupBranchPointer> =
            (existing.filterNot { it.branchId == extra.branchId } + extra)
                .sortedByDescending { it.updatedAt }
                .take(64)
    }
}
