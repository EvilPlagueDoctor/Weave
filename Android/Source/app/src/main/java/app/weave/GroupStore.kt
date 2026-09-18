package app.weave

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Account-scoped group cache, moderation state and branch preferences.
 *
 * Everything here is a daemon-owned PrivateVault participant. Public DHT data can be rebuilt,
 * but "which branch I follow", pending moderation, memberships and discovery history are private
 * to the active VeilKnit account.
 */
class GroupStore(context: Context) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context)
    private val groups = mutableListOf<GroupRecord>()
    private val pulses = mutableMapOf<String, GroupPulse>()
    private val moderation = mutableListOf<GroupModerationTask>()
    private val branches = mutableMapOf<String, MutableList<GroupBranchPointer>>()
    private val branchHeaders = mutableMapOf<String, GroupBranchHeader>()
    private val selectedBranches = mutableMapOf<String, String>()
    /** Groups whose branch choice was explicitly made by the person (or by claiming). */
    private val explicitBranchSelections = mutableSetOf<String>()
    private val directoryByAdvertiser = mutableMapOf<String, MutableList<GroupDirectoryEntry>>()
    private val seenEvents = LinkedHashSet<String>()
    private val pending = mutableMapOf<String, GroupPendingItem>()
    private val postDetails = mutableMapOf<String, GroupPostDetail>()
    private val privateIntakeKeys = mutableMapOf<String, String>()

    var revision by mutableLongStateOf(0L)
        private set

    private val preferredModeState = mutableStateOf(BrowseMode.People)

    var preferredMode: BrowseMode
        get() = preferredModeState.value
        set(value) {
            if (preferredModeState.value == value) return
            preferredModeState.value = value
            persistUi()
        }

    init { vault.register(this) }

    override fun onVaultAttached() = load()

    override fun onVaultDetached() {
        groups.clear()
        pulses.clear()
        moderation.clear()
        branches.clear()
        branchHeaders.clear()
        selectedBranches.clear()
        explicitBranchSelections.clear()
        directoryByAdvertiser.clear()
        seenEvents.clear()
        pending.clear()
        postDetails.clear()
        privateIntakeKeys.clear()
        preferredMode = BrowseMode.People
        revision++
    }

    @Synchronized fun all(): List<GroupRecord> = groups.toList()
    @Synchronized fun byId(groupId: String): GroupRecord? = groups.firstOrNull { it.groupId == groupId }

    @Synchronized
    fun owned(ownerId: String): List<GroupRecord> =
        groups.mapNotNull { group ->
            val ownsBranch = branches[group.groupId].orEmpty().any { it.ownerMainDht == ownerId }
            val moderatesBranch = branchHeaders.values.any { header ->
                header.group.groupId == group.groupId &&
                    header.moderators.any { it.moderatorMainDht == ownerId }
            }
            when {
                group.ownerId == ownerId || group.role in setOf(GroupRole.Creator, GroupRole.Claimer, GroupRole.Admin) ->
                    group
                ownsBranch ->
                    if (group.role == GroupRole.Member) group.copy(role = GroupRole.Claimer) else group
                moderatesBranch ->
                    group.copy(role = GroupRole.Moderator)
                else -> null
            }
        }.distinctBy { it.groupId }.sortedBy { it.name.lowercase() }

    @Synchronized
    fun joined(): List<GroupRecord> =
        groups.filter { it.joined }.sortedWith(
            compareByDescending<GroupRecord> { pulse(it.groupId).updatedAt.takeIf { ts -> ts > 0 } ?: it.updatedAt }
                .thenBy { it.name.lowercase() }
        )

    @Synchronized
    fun search(query: String): List<GroupRecord> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return groups.sortedBy { it.name.lowercase() }
        return groups.map { group ->
            val haystack = "${group.name} ${group.description} ${group.tags.joinToString(" ")}".lowercase()
            group to terms.count { it in haystack }
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<GroupRecord, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    private fun pulseKey(groupId: String, branchId: String): String = "$groupId|$branchId"

    @Synchronized
    fun pulse(groupId: String): GroupPulse {
        val branch = selectedBranch(groupId)
        if (branch != null) pulses[pulseKey(groupId, branch.branchId)]?.let { return it }
        pulses[pulseKey(groupId, GroupBranchNetwork.originalBranchId(groupId))]?.let { return it }
        return pulses[groupId] ?: GroupPulse(groupId)
    }

    @Synchronized
    fun pulse(groupId: String, branchId: String): GroupPulse =
        pulses[pulseKey(groupId, branchId)] ?: GroupPulse(groupId)

    @Synchronized
    fun upsertPulse(pulse: GroupPulse, branchId: String = selectedBranches[pulse.groupId]
        ?: GroupBranchNetwork.originalBranchId(pulse.groupId)) {
        if (pulse.groupId.isBlank()) return
        val key = pulseKey(pulse.groupId, branchId)
        val old = pulses[key]
        if (old != null && old.generation > pulse.generation) return
        pulses[key] = pulse
        if (vault.attached) runCatching {
            vault.putText("groups/pulse/${safeKey(pulse.groupId)}/${safeKey(branchId)}.json", pulse.toJson().toString())
        }
        revision++
    }

    @Synchronized
    fun upsertDiscovered(group: GroupRecord, pulse: GroupPulse? = null, branchId: String? = null) {
        val index = groups.indexOfFirst { it.groupId == group.groupId }
        if (index < 0) groups += group else {
            val local = groups[index]
            groups[index] = group.copy(role = local.role, joined = local.joined)
        }
        pulse?.let { upsertPulse(it, branchId ?: selectedBranches[group.groupId]
            ?: GroupBranchNetwork.originalBranchId(group.groupId)) }
        persist()
    }

    @Synchronized
    fun createGroup(
        ownerId: String,
        name: String,
        description: String,
        thumbnailBase64: String? = null,
        tags: List<String>,
        policy: GroupPolicy,
        featured: FeaturedSlot,
    ): GroupRecord {
        val now = System.currentTimeMillis()
        val group = GroupRecord(
            groupId = UUID.randomUUID().toString(),
            ownerId = ownerId,
            name = name.trim().ifBlank { "Untitled group" }.take(120),
            description = description.trim().take(2000),
            thumbnailBase64 = thumbnailBase64?.takeIf { it.isNotBlank() },
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(32),
            policy = policy,
            featured = featured,
            role = GroupRole.Creator,
            joined = true,
            createdAt = now,
            updatedAt = now,
            approximateMembers = 1,
        )
        groups += group
        if (policy.visibility == GroupVisibility.MembersOnly) {
            privateIntakeKeys[group.groupId] = Base64.encodeToString(GroupIntakeCrypto.newKey(), Base64.NO_WRAP)
        }
        val branchId = GroupBranchNetwork.originalBranchId(group.groupId)
        selectedBranches[group.groupId] = branchId
        pulses[pulseKey(group.groupId, branchId)] = GroupPulse(group.groupId)
        persist()
        return group
    }

    @Synchronized
    fun updateGroup(updated: GroupRecord) {
        val index = groups.indexOfFirst { it.groupId == updated.groupId }
        if (index < 0) groups += updated else groups[index] = updated.copy(updatedAt = System.currentTimeMillis())
        if (updated.policy.visibility == GroupVisibility.MembersOnly && privateIntakeKeys[updated.groupId].isNullOrBlank()) {
            privateIntakeKeys[updated.groupId] = Base64.encodeToString(GroupIntakeCrypto.newKey(), Base64.NO_WRAP)
        }
        persist()
    }

    @Synchronized
    fun privateIntakeKey(groupId: String): ByteArray? = privateIntakeKeys[groupId]
        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        ?.takeIf { it.size == 32 }

    /** Called by the future invite/join key-distribution flow after authenticated key delivery. */
    @Synchronized
    fun installPrivateIntakeKey(groupId: String, key: ByteArray) {
        require(key.size == 32) { "Private group intake key must be 256 bits" }
        privateIntakeKeys[groupId] = Base64.encodeToString(key, Base64.NO_WRAP)
        persist()
    }

    @Synchronized
    fun privateIntakeKeys(): Map<String, ByteArray> = privateIntakeKeys.mapNotNull { (group, encoded) ->
        runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.size == 32 }
            ?.let { group to it }
    }.toMap()

    @Synchronized
    fun setPublishedRoot(groupId: String, recordKey: String) {
        val index = groups.indexOfFirst { it.groupId == groupId }
        if (index < 0) return
        groups[index] = groups[index].copy(rootRecordKey = recordKey, updatedAt = System.currentTimeMillis())
        persist()
    }

    @Synchronized
    fun setJoined(groupId: String, joined: Boolean) {
        val index = groups.indexOfFirst { it.groupId == groupId }
        if (index < 0) return
        groups[index] = groups[index].copy(joined = joined)
        persist()
    }

    /**
     * Update branch bookkeeping without touching the encrypted vault. Callers that are ingesting a
     * directory/header batch can make all of their in-memory changes first and persist once at the
     * end instead of serializing the entire Groups state for every single pointer.
     */
    private fun rememberBranchInMemory(
        pointer: GroupBranchPointer,
        selectIfNone: Boolean,
        ownedByMe: Boolean = false,
    ) {
        val list = branches.getOrPut(pointer.groupId) { mutableListOf() }
        list.removeAll { it.branchId == pointer.branchId }
        list += pointer
        list.sortByDescending { it.updatedAt }
        val hasExplicitSelection = pointer.groupId in explicitBranchSelections
        if (selectedBranches[pointer.groupId].isNullOrBlank() || (selectIfNone && !hasExplicitSelection)) {
            selectedBranches[pointer.groupId] = pointer.branchId
        }
        if (ownedByMe) {
            val i = groups.indexOfFirst { it.groupId == pointer.groupId }
            if (i >= 0 && pointer.kind == GroupBranchKind.Claim && groups[i].role != GroupRole.Creator) {
                groups[i] = groups[i].copy(role = GroupRole.Claimer, joined = true)
            }
        }
    }

    @Synchronized
    fun rememberBranch(pointer: GroupBranchPointer, selectIfNone: Boolean, ownedByMe: Boolean = false) {
        rememberBranchInMemory(pointer, selectIfNone, ownedByMe)
        persist()
    }

    private fun branchHeaderKey(groupId: String, branchId: String): String = "$groupId|$branchId"

    @Synchronized
    fun rememberBranchHeader(header: GroupBranchHeader) {
        branchHeaders[branchHeaderKey(header.group.groupId, header.branchId)] = header
        // Original is the sensible automatic default, but only prefer it while the choice is still
        // implicit. Once the person chooses Original/Claim explicitly, refresh may not change it.
        rememberBranchInMemory(header.pointer(), selectIfNone = header.kind == GroupBranchKind.Original)
        persist()
    }

    /** Store a verified branch header and every branch it advertises in one vault transaction. */
    @Synchronized
    fun rememberBranchHeaderAndKnown(header: GroupBranchHeader, ownedByMe: Boolean = false) {
        branchHeaders[branchHeaderKey(header.group.groupId, header.branchId)] = header
        rememberBranchInMemory(
            header.pointer(),
            selectIfNone = header.kind == GroupBranchKind.Original,
            ownedByMe = ownedByMe,
        )
        header.knownBranches.forEach { pointer ->
            rememberBranchInMemory(pointer, selectIfNone = false, ownedByMe = false)
        }
        persist()
    }

    @Synchronized
    fun selectedHeader(groupId: String): GroupBranchHeader? {
        val pointer = selectedBranch(groupId) ?: return null
        return branchHeaders[branchHeaderKey(groupId, pointer.branchId)]
    }

    @Synchronized
    fun branchesFor(groupId: String): List<GroupBranchPointer> =
        branches[groupId].orEmpty().sortedWith(
            compareBy<GroupBranchPointer> { if (it.kind == GroupBranchKind.Original) 0 else 1 }
                .thenByDescending { it.updatedAt }
        )

    @Synchronized
    fun selectedBranch(groupId: String): GroupBranchPointer? {
        val list = branches[groupId].orEmpty()
        val wanted = selectedBranches[groupId]
        return list.firstOrNull { it.branchId == wanted }
            ?: list.firstOrNull { it.kind == GroupBranchKind.Original }
            ?: list.firstOrNull()
    }

    @Synchronized
    fun selectBranch(groupId: String, branchId: String) {
        if (branches[groupId].orEmpty().none { it.branchId == branchId }) return
        selectedBranches[groupId] = branchId
        explicitBranchSelections += groupId
        persist()
    }

    @Synchronized
    fun ownClaim(groupId: String, ownMainDht: String): GroupBranchPointer? =
        branches[groupId].orEmpty().firstOrNull {
            it.kind == GroupBranchKind.Claim && it.ownerMainDht == ownMainDht
        }

    @Synchronized
    fun directoryEntriesForSelf(ownMainDht: String): List<GroupDirectoryEntry> =
        groups.flatMap { group ->
            branches[group.groupId].orEmpty()
                .filter { it.ownerMainDht == ownMainDht }
                .mapNotNull { pointer ->
                    if (group.policy.visibility == GroupVisibility.MembersOnly) return@mapNotNull null
                    GroupDirectoryEntry(
                        groupId = group.groupId,
                        name = group.name,
                        advertiserMainDht = ownMainDht,
                        creatorMainDht = group.ownerId,
                        creatorRoot = pointer.creatorRoot,
                        branchId = pointer.branchId,
                        branchRoot = pointer.branchRoot,
                        kind = pointer.kind,
                        visibility = group.policy.visibility,
                        updatedAt = pointer.updatedAt,
                    )
                }
        }.distinctBy { it.groupId to it.branchId }.take(GroupDirectoryNetwork.MAX_DIRECTORY_ENTRIES)

    @Synchronized
    fun upsertDirectoryEntries(advertiserMainDht: String, entries: List<GroupDirectoryEntry>) {
        val clean = entries
            .filter { it.advertiserMainDht == advertiserMainDht }
            .filter { it.visibility != GroupVisibility.MembersOnly }
            .distinctBy { it.groupId to it.branchId }
            .toMutableList()
        directoryByAdvertiser[advertiserMainDht] = clean
        clean.forEach { entry ->
            rememberBranchInMemory(
                GroupBranchPointer(
                    groupId = entry.groupId,
                    branchId = entry.branchId,
                    branchRoot = entry.branchRoot,
                    ownerMainDht = entry.advertiserMainDht,
                    kind = entry.kind,
                    creatorRoot = entry.creatorRoot,
                    updatedAt = entry.updatedAt,
                ),
                selectIfNone = entry.kind == GroupBranchKind.Original,
                ownedByMe = false,
            )
            if (groups.none { it.groupId == entry.groupId }) {
                groups += GroupRecord(
                    groupId = entry.groupId,
                    rootRecordKey = entry.creatorRoot,
                    ownerId = entry.creatorMainDht,
                    name = entry.name,
                    policy = GroupPolicy(visibility = entry.visibility),
                    role = GroupRole.Member,
                    joined = false,
                    createdAt = entry.updatedAt,
                    updatedAt = entry.updatedAt,
                )
            }
        }
        persist()
    }

    @Synchronized
    fun directoryFor(advertiserMainDht: String): List<GroupDirectoryEntry> =
        directoryByAdvertiser[advertiserMainDht].orEmpty().toList()

    /**
     * Returns true once per local event key. The bounded persisted set makes direct delivery,
     * mailbox retrieval, ServiceRequest viewing and claimer reconciliation idempotent.
     */
    @Synchronized
    fun markEventSeen(eventKey: String): Boolean {
        if (eventKey.isBlank() || eventKey in seenEvents) return false
        seenEvents += eventKey
        while (seenEvents.size > MAX_SEEN_EVENTS) seenEvents.remove(seenEvents.first())
        persist()
        return true
    }

    @Synchronized
    fun rememberPendingEnvelope(event: GroupEvent, ref: WeaveObjectRef?, sourceMainDht: String) {
        pending[event.eventId] = GroupPendingItem(
            event = event,
            contentRef = ref,
            sourceMainDht = sourceMainDht,
            publicSpectator = false,
        )
        enforcePendingCap()
        persist()
    }

    @Synchronized
    fun rememberPublicPending(event: GroupEvent, ref: WeaveObjectRef?, expiresAt: Long) {
        val now = System.currentTimeMillis()
        val normalizedExpiry = when {
            expiresAt <= 0L -> now + GroupBranchNetwork.OPEN_INTAKE_TTL_SECONDS * 1000L
            expiresAt < 10_000_000_000L -> expiresAt * 1000L // tolerate daemon seconds
            else -> expiresAt
        }
        pending[event.eventId] = GroupPendingItem(
            event = event,
            contentRef = ref,
            publicSpectator = true,
            expiresAt = normalizedExpiry,
        )
        prunePending(now)
        enforcePendingCap()
        persist()
    }

    @Synchronized
    fun rememberEncryptedSpectatorPending(event: GroupEvent, ref: WeaveObjectRef?, expiresAt: Long) {
        val now = System.currentTimeMillis()
        val normalizedExpiry = when {
            expiresAt <= 0L -> now + GroupBranchNetwork.OPEN_INTAKE_TTL_SECONDS * 1000L
            expiresAt < 10_000_000_000L -> expiresAt * 1000L
            else -> expiresAt
        }
        pending[event.eventId] = GroupPendingItem(
            event = event,
            contentRef = ref,
            publicSpectator = false,
            encryptedSpectator = true,
            expiresAt = normalizedExpiry,
        )
        prunePending(now)
        enforcePendingCap()
        persist()
    }

    private fun postDetailKey(groupId: String, branchId: String, conversationId: String): String =
        "$groupId|$branchId|$conversationId"

    @Synchronized
    fun rememberPostDetail(detail: GroupPostDetail) {
        postDetails[postDetailKey(detail.groupId, detail.branchId, detail.conversationId)] = detail
        while (postDetails.size > MAX_POST_DETAILS) postDetails.entries.firstOrNull()?.key?.let(postDetails::remove) ?: break
        revision++
    }

    @Synchronized
    fun postDetail(groupId: String, branchId: String, conversationId: String): GroupPostDetail? =
        postDetails[postDetailKey(groupId, branchId, conversationId)]

    @Synchronized
    fun pendingByEvent(eventId: String): GroupPendingItem? = pending[eventId]

    @Synchronized
    fun pendingFor(groupId: String, includePublic: Boolean = true): List<GroupPendingItem> {
        prunePending(System.currentTimeMillis())
        return pending.values.filter {
            it.event.groupId == groupId && (includePublic || !it.publicSpectator)
        }.sortedBy { it.event.createdAt }
    }

    @Synchronized
    fun clearPending(eventId: String) {
        pending.remove(eventId)
        persist()
    }

    @Synchronized
    fun clearPendingForObject(objectId: String) {
        val ids = pending.values.filter { it.event.objectId == objectId }.map { it.event.eventId }
        if (ids.isEmpty()) return
        ids.forEach(pending::remove)
        persist()
    }

    @Synchronized
    fun pendingTasks(groupId: String? = null): List<GroupModerationTask> =
        moderation.filter {
            it.state == GroupModerationState.Pending && (groupId == null || it.groupId == groupId)
        }.sortedBy { it.createdAt }

    @Synchronized fun pendingActionCount(): Int =
        moderation.count { it.state == GroupModerationState.Pending }

    @Synchronized
    fun moderationTask(taskId: String): GroupModerationTask? = moderation.firstOrNull { it.taskId == taskId }

    @Synchronized
    fun addModerationTask(task: GroupModerationTask) {
        if (moderation.none { it.taskId == task.taskId }) {
            if (moderation.size >= MAX_MODERATION_TASKS) {
                val terminal = moderation.filter { it.state != GroupModerationState.Pending }.minByOrNull { it.createdAt }
                if (terminal != null) moderation.remove(terminal)
                else if (moderation.isNotEmpty()) moderation.removeAt(0)
            }
            moderation += task
            persist()
        }
    }

    @Synchronized
    fun decide(taskId: String, state: GroupModerationState) {
        val index = moderation.indexOfFirst { it.taskId == taskId }
        if (index < 0) return
        moderation[index] = moderation[index].copy(state = state)
        persist()
    }

    private fun prunePending(now: Long) {
        pending.entries.removeAll { (_, item) -> item.expiresAt > 0L && item.expiresAt <= now }
    }

    private fun enforcePendingCap() {
        while (pending.size > MAX_PENDING_ITEMS) {
            val victim = pending.entries.minByOrNull { it.value.event.createdAt }?.key ?: break
            pending.remove(victim)
        }
    }

    /** Lightweight, untrusted hints suitable for gossip. DHT verification still decides truth. */
    @Synchronized
    fun gossipHints(limit: Int = 16): List<GroupGossipHint> = groups
        .asSequence()
        .filter { it.policy.visibility != GroupVisibility.MembersOnly }
        .flatMap { group ->
            branches[group.groupId].orEmpty().asSequence().map { pointer ->
                val pulse = pulses[pulseKey(group.groupId, pointer.branchId)]
                GroupGossipHint(
                    pointer = pointer,
                    pulseGeneration = pulse?.generation ?: 0L,
                    pulseUpdatedAt = pulse?.updatedAt ?: 0L,
                )
            }
        }
        .distinctBy { it.pointer.groupId to it.pointer.branchId }
        .sortedByDescending { maxOf(it.pulseUpdatedAt, it.pointer.updatedAt) }
        .take(limit.coerceIn(1, 32))
        .toList()

    /** Human-readable account/group ownership snapshot for copied diagnostics. */
    @Synchronized
    fun responsibilityLines(ownMainDht: String): List<String> {
        if (ownMainDht.isBlank()) return listOf("(daemon identity unavailable)")
        val out = mutableListOf<String>()
        groups.sortedBy { it.name.lowercase() }.forEach { group ->
            val selected = selectedBranch(group.groupId)
            val groupBranches = branches[group.groupId].orEmpty()
            groupBranches.filter { it.ownerMainDht == ownMainDht }.forEach { branch ->
                out += buildString {
                    append(group.name.ifBlank { group.groupId })
                    append(" | ")
                    append(if (branch.kind == GroupBranchKind.Original) "creator/original owner" else "claim owner")
                    append(" | branch=").append(branch.branchId)
                    if (selected?.branchId == branch.branchId) append(" | selected")
                    append(" | pending=").append(pending.values.count { it.event.groupId == group.groupId })
                }
            }
            var foundModeratorGrant = false
            branchHeaders.values
                .filter { it.group.groupId == group.groupId }
                .forEach { header ->
                    header.moderators.firstOrNull { it.moderatorMainDht == ownMainDht }?.let { grant ->
                        foundModeratorGrant = true
                        out += buildString {
                            append(group.name.ifBlank { group.groupId })
                            append(" | secondary moderator")
                            append(" | branch=").append(header.branchId)
                            append(" | owner=").append(header.ownerMainDht)
                            append(" | posts=").append(grant.canModeratePosts)
                            append(" members=").append(grant.canApproveMembers)
                            append(" reports=").append(grant.canHandleReports)
                            if (selected?.branchId == header.branchId) append(" | selected")
                        }
                    }
                }
            if (!foundModeratorGrant && group.role == GroupRole.Moderator) {
                out += "${group.name.ifBlank { group.groupId }} | secondary moderator (cached role; branch grant not refreshed yet)"
            }
        }
        return out.distinct().ifEmpty { listOf("(no created/claimed/moderated groups known locally)") }
    }

    @Synchronized
    fun knownPulseGeneration(groupId: String, branchId: String): Long =
        pulses[pulseKey(groupId, branchId)]?.generation ?: 0L

    @Synchronized
    fun branchKnown(groupId: String, branchId: String): Boolean =
        branches[groupId].orEmpty().any { it.branchId == branchId }

    @Synchronized
    private fun load() {
        groups.clear(); pulses.clear(); moderation.clear(); branches.clear()
        selectedBranches.clear(); explicitBranchSelections.clear(); directoryByAdvertiser.clear(); seenEvents.clear(); pending.clear(); postDetails.clear(); privateIntakeKeys.clear()
        if (!vault.attached) { revision++; return }
        runCatching {
            val root = JSONObject(vault.getText(STATE_KEY) ?: "{}")
            val arr = root.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                runCatching { GroupRecord.fromJson(it) }.getOrNull()?.let(groups::add)
            }
            val tasks = root.optJSONArray("moderation") ?: JSONArray()
            for (i in 0 until tasks.length()) tasks.optJSONObject(i)?.let {
                runCatching { GroupModerationTask.fromJson(it) }.getOrNull()?.let(moderation::add)
            }
            val branchesJson = root.optJSONArray("branches") ?: JSONArray()
            for (i in 0 until branchesJson.length()) {
                val ptr = branchesJson.optJSONObject(i)?.let(GroupBranchPointer::fromJson) ?: continue
                branches.getOrPut(ptr.groupId) { mutableListOf() }.add(ptr)
            }
            val selected = root.optJSONObject("selected_branches") ?: JSONObject()
            val selectedKeys = selected.keys()
            while (selectedKeys.hasNext()) {
                val key = selectedKeys.next()
                selectedBranches[key] = selected.optString(key)
            }
            val explicit = root.optJSONArray("explicit_branch_selections")
            if (explicit != null) {
                for (i in 0 until explicit.length()) {
                    explicit.optString(i).takeIf { it.isNotBlank() }?.let(explicitBranchSelections::add)
                }
            } else {
                // v2 did not distinguish automatic/default selection from a user's choice. A
                // selected Claim is the safest migration signal that the non-default branch was
                // deliberately chosen (or owned), so preserve that as explicit.
                selectedBranches.forEach { (groupId, branchId) ->
                    if (branches[groupId].orEmpty().any { it.branchId == branchId && it.kind == GroupBranchKind.Claim }) {
                        explicitBranchSelections += groupId
                    }
                }
            }
            val dirs = root.optJSONObject("directories") ?: JSONObject()
            val dirKeys = dirs.keys()
            while (dirKeys.hasNext()) {
                val advertiser = dirKeys.next()
                val a = dirs.optJSONArray(advertiser) ?: continue
                val list = mutableListOf<GroupDirectoryEntry>()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let(GroupDirectoryEntry::fromJson)?.let(list::add)
                directoryByAdvertiser[advertiser] = list
            }
            val seen = root.optJSONArray("seen_events") ?: JSONArray()
            for (i in 0 until seen.length()) seen.optString(i).takeIf { it.isNotBlank() }?.let(seenEvents::add)
            val pendingJson = root.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until pendingJson.length()) {
                pendingJson.optJSONObject(i)?.let(GroupPendingItem::fromJson)?.let { pending[it.event.eventId] = it }
            }
            val secrets = root.optJSONObject("private_intake_keys") ?: JSONObject()
            val secretKeys = secrets.keys()
            while (secretKeys.hasNext()) {
                val group = secretKeys.next()
                secrets.optString(group).takeIf { it.isNotBlank() }?.let { privateIntakeKeys[group] = it }
            }

            val ui = JSONObject(vault.getText(UI_KEY) ?: "{}")
            preferredMode = runCatching {
                BrowseMode.valueOf(ui.optString("mode", BrowseMode.People.name))
            }.getOrDefault(BrowseMode.People)

            groups.forEach { group ->
                branches[group.groupId].orEmpty().forEach { branch ->
                    val path = "groups/pulse/${safeKey(group.groupId)}/${safeKey(branch.branchId)}.json"
                    val pulseText = vault.getText(path)
                    if (!pulseText.isNullOrBlank()) runCatching {
                        GroupPulse.fromJson(JSONObject(pulseText))
                    }.getOrNull()?.let { pulses[pulseKey(group.groupId, branch.branchId)] = it }
                }
            }
            prunePending(System.currentTimeMillis())
            enforcePendingCap()
            while (moderation.size > MAX_MODERATION_TASKS) {
                val terminal = moderation.filter { it.state != GroupModerationState.Pending }.minByOrNull { it.createdAt }
                if (terminal != null) moderation.remove(terminal) else moderation.removeAt(0)
            }
        }
        revision++
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) { revision++; return }
        runCatching {
            val root = JSONObject()
                .put("v", 3)
                .put("groups", JSONArray().apply { groups.forEach { put(it.toLocalJson()) } })
                .put("moderation", JSONArray().apply { moderation.forEach { put(it.toJson()) } })
                .put("branches", JSONArray().apply {
                    branches.values.flatten().distinctBy { it.groupId to it.branchId }.forEach { put(it.toJson()) }
                })
                .put("selected_branches", JSONObject().apply {
                    selectedBranches.forEach { (group, branch) -> put(group, branch) }
                })
                .put("explicit_branch_selections", JSONArray(explicitBranchSelections.toList()))
                .put("directories", JSONObject().apply {
                    directoryByAdvertiser.forEach { (advertiser, list) ->
                        put(advertiser, JSONArray().apply { list.forEach { put(it.toJson()) } })
                    }
                })
                .put("seen_events", JSONArray(seenEvents.toList()))
                .put("pending", JSONArray().apply { pending.values.forEach { put(it.toJson()) } })
                .put("private_intake_keys", JSONObject().apply {
                    privateIntakeKeys.forEach { (group, key) -> put(group, key) }
                })
            vault.putText(STATE_KEY, root.toString())
        }
        revision++
    }

    private fun persistUi() {
        if (!vault.attached) return
        runCatching { vault.putText(UI_KEY, JSONObject().put("mode", preferredMode.name).toString()) }
    }

    companion object {
        private const val STATE_KEY = "groups/state-v2.json"
        private const val UI_KEY = "groups/ui.json"
        private const val MAX_SEEN_EVENTS = 4096
        private const val MAX_PENDING_ITEMS = 2000
        private const val MAX_MODERATION_TASKS = 2000
        private const val MAX_POST_DETAILS = 256
        private fun safeKey(value: String): String =
            value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(160)
    }
}
