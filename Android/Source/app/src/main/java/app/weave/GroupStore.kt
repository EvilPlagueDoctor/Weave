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
    private val selectedBranches = mutableMapOf<String, String>()
    private val directoryByAdvertiser = mutableMapOf<String, MutableList<GroupDirectoryEntry>>()
    private val seenEvents = LinkedHashSet<String>()
    private val pending = mutableMapOf<String, GroupPendingItem>()
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
        selectedBranches.clear()
        directoryByAdvertiser.clear()
        seenEvents.clear()
        pending.clear()
        privateIntakeKeys.clear()
        preferredMode = BrowseMode.People
        revision++
    }

    @Synchronized fun all(): List<GroupRecord> = groups.toList()
    @Synchronized fun byId(groupId: String): GroupRecord? = groups.firstOrNull { it.groupId == groupId }

    @Synchronized
    fun owned(ownerId: String): List<GroupRecord> =
        groups.filter { group ->
            group.ownerId == ownerId ||
                group.role in setOf(GroupRole.Creator, GroupRole.Claimer, GroupRole.Admin) ||
                branches[group.groupId].orEmpty().any { it.ownerMainDht == ownerId }
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

    @Synchronized
    fun rememberBranch(pointer: GroupBranchPointer, selectIfNone: Boolean, ownedByMe: Boolean = false) {
        val list = branches.getOrPut(pointer.groupId) { mutableListOf() }
        list.removeAll { it.branchId == pointer.branchId }
        list += pointer
        list.sortByDescending { it.updatedAt }
        if (selectIfNone || selectedBranches[pointer.groupId].isNullOrBlank()) {
            selectedBranches[pointer.groupId] = pointer.branchId
        }
        if (ownedByMe) {
            val i = groups.indexOfFirst { it.groupId == pointer.groupId }
            if (i >= 0 && pointer.kind == GroupBranchKind.Claim && groups[i].role != GroupRole.Creator) {
                groups[i] = groups[i].copy(role = GroupRole.Claimer, joined = true)
            }
        }
        persist()
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
            rememberBranch(
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
        persist()
    }

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

    @Synchronized
    private fun load() {
        groups.clear(); pulses.clear(); moderation.clear(); branches.clear()
        selectedBranches.clear(); directoryByAdvertiser.clear(); seenEvents.clear(); pending.clear(); privateIntakeKeys.clear()
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
        }
        revision++
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) { revision++; return }
        runCatching {
            val root = JSONObject()
                .put("v", 2)
                .put("groups", JSONArray().apply { groups.forEach { put(it.toLocalJson()) } })
                .put("moderation", JSONArray().apply { moderation.forEach { put(it.toJson()) } })
                .put("branches", JSONArray().apply {
                    branches.values.flatten().distinctBy { it.groupId to it.branchId }.forEach { put(it.toJson()) }
                })
                .put("selected_branches", JSONObject().apply {
                    selectedBranches.forEach { (group, branch) -> put(group, branch) }
                })
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
        private fun safeKey(value: String): String =
            value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(160)
    }
}
