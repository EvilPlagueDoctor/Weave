package app.weave

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

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
            moderators = previous?.moderators.orEmpty(),
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
        store.rememberBranch(header.pointer(), selectIfNone = true, ownedByMe = true)
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
            knownBranches = listOf(pointerToCreator),
        )
        writeHeader(owned.storeId, header)
        writePulse(owned.storeId, GroupPulse(group.groupId, updatedAt = now))
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
        store.rememberBranch(header.pointer(), selectIfNone = false, ownedByMe = true)
        return header
    }

    fun readHeader(branchRoot: String): GroupBranchHeader? = runCatching {
        val result = client.readPublicStore(branchRoot, listOf(HEADER_SUBKEY), true)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupBranchHeader.fromJson(JSONObject(bytes.decodeToString()))
    }.getOrNull()

    fun readPulse(branchRoot: String): GroupPulse? = runCatching {
        val result = client.readPublicStore(branchRoot, listOf(PULSE_SUBKEY), false)
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
            serviceIdsHex = listOf(OPEN_INTAKE_SERVICE_ID, PRIVATE_INTAKE_SERVICE_ID),
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

        const val OPEN_INTAKE_TTL_SECONDS = 15L * 60L

        // 32-byte SHA-256 identifiers encoded as hex; stable across every Weave install.
        val OPEN_INTAKE_SERVICE_ID: String = groupSha256Hex("weave/group/open-intake/v2".encodeToByteArray())
        val OPEN_INTAKE_MANIFEST_HASH: String = groupSha256Hex("weave/group/open-intake/manifest/v2".encodeToByteArray())
        val PRIVATE_INTAKE_SERVICE_ID: String = groupSha256Hex("weave/group/private-intake/v2".encodeToByteArray())
        val PRIVATE_INTAKE_MANIFEST_HASH: String = groupSha256Hex("weave/group/private-intake/manifest/v2".encodeToByteArray())

        fun originalBranchId(groupId: String): String =
            "original-${groupSha256Hex(groupId.encodeToByteArray()).take(24)}"

        private fun instanceId(groupId: String, branchId: String): String =
            groupSha256Hex("$groupId|$branchId".encodeToByteArray())

        private fun privateInstanceId(key: ByteArray): String =
            groupSha256Hex(key + "|weave-private-intake|".encodeToByteArray())

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
