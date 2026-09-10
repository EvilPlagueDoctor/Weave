package app.weave

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * One group can have several moderation branches. ORIGINAL is the creator's view; CLAIM is an
 * independent moderation view of that same group. A true fork is a new groupId and is therefore
 * deliberately not represented by this enum.
 */
enum class GroupBranchKind { Original, Claim }

/** Current decision in one moderation branch. */
enum class GroupPostState { Pending, Visible, Removed }

enum class GroupEventKind {
    GroupCreated,
    ClaimCreated,
    BranchObserved,
    ConversationCreated,
    PostSubmitted,
    PostAllowed,
    PostRemoved,
    PostRestored,
    JoinRequested,
    JoinApproved,
    JoinRejected,
    ReportSubmitted,
    ModeratorGranted,
    ModeratorRevoked,
    PolicyChanged,
    FeaturedChanged,
}

/**
 * A profile-linked advertisement. Discovery is intentionally user-first:
 * discover a person -> read profile-root subkey 2 -> learn groups they created/claim.
 *
 * The advertiser field MUST match the profile whose app root supplied the directory. That binds
 * a claim to the claimer's normal Weave/VeilKnit identity without accepting self-declared keys.
 */
data class GroupDirectoryEntry(
    val groupId: String,
    val name: String,
    val advertiserMainDht: String,
    val creatorMainDht: String,
    val creatorRoot: String,
    val branchId: String,
    val branchRoot: String,
    val kind: GroupBranchKind,
    val visibility: GroupVisibility,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("name", name.take(120))
        .put("advertiser", advertiserMainDht)
        .put("creator", creatorMainDht)
        .put("creator_root", creatorRoot)
        .put("branch_id", branchId)
        .put("branch_root", branchRoot)
        .put("kind", kind.name)
        .put("visibility", visibility.name)
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): GroupDirectoryEntry? = runCatching {
            val entry = GroupDirectoryEntry(
                groupId = o.getString("group_id"),
                name = o.optString("name", "Unnamed group").take(120),
                advertiserMainDht = o.getString("advertiser"),
                creatorMainDht = o.getString("creator"),
                creatorRoot = o.getString("creator_root"),
                branchId = o.getString("branch_id"),
                branchRoot = o.getString("branch_root"),
                kind = GroupBranchKind.valueOf(o.getString("kind")),
                visibility = GroupVisibility.valueOf(o.getString("visibility")),
                updatedAt = o.optLong("updated_at"),
            )
            if (entry.groupId.isBlank() || entry.creatorRoot.isBlank() || entry.branchRoot.isBlank()) null else entry
        }.getOrNull()
    }
}

data class GroupBranchPointer(
    val groupId: String,
    val branchId: String,
    val branchRoot: String,
    val ownerMainDht: String,
    val kind: GroupBranchKind,
    val creatorRoot: String,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("group_id", groupId)
        .put("branch_id", branchId)
        .put("branch_root", branchRoot)
        .put("owner", ownerMainDht)
        .put("kind", kind.name)
        .put("creator_root", creatorRoot)
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): GroupBranchPointer? = runCatching {
            GroupBranchPointer(
                groupId = o.getString("group_id"),
                branchId = o.getString("branch_id"),
                branchRoot = o.getString("branch_root"),
                ownerMainDht = o.getString("owner"),
                kind = GroupBranchKind.valueOf(o.getString("kind")),
                creatorRoot = o.getString("creator_root"),
                updatedAt = o.optLong("updated_at"),
            )
        }.getOrNull()
    }
}

/**
 * Secondary moderators belong to one branch only. They do not mirror it and cannot become
 * claimers merely by being moderators.
 */
data class GroupModeratorGrant(
    val moderatorMainDht: String,
    val canModeratePosts: Boolean = true,
    val canApproveMembers: Boolean = true,
    val canHandleReports: Boolean = true,
    val grantedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("moderator", moderatorMainDht)
        .put("posts", canModeratePosts)
        .put("members", canApproveMembers)
        .put("reports", canHandleReports)
        .put("granted_at", grantedAt)

    companion object {
        fun fromJson(o: JSONObject): GroupModeratorGrant? = runCatching {
            GroupModeratorGrant(
                moderatorMainDht = o.getString("moderator"),
                canModeratePosts = o.optBoolean("posts", true),
                canApproveMembers = o.optBoolean("members", true),
                canHandleReports = o.optBoolean("reports", true),
                grantedAt = o.optLong("granted_at"),
            )
        }.getOrNull()
    }
}

/**
 * Current branch index entry. The hash is permanent branch knowledge; messageRef is present only
 * while the branch advertises the content as visible. Pending/Removed MUST NOT retain it.
 */
data class GroupPostIndexEntry(
    val postId: String,
    val conversationId: String,
    val postHash: String,
    val state: GroupPostState,
    val messageRef: WeaveObjectRef? = null,
    val decidedBy: String = "",
    val decidedAt: Long = 0L,
    val reason: String = "",
) {
    init {
        require(state == GroupPostState.Visible || messageRef == null) {
            "Pending/removed branch entries may not retain a content reference"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("post_id", postId)
        .put("conversation_id", conversationId)
        .put("hash", postHash)
        .put("state", state.name)
        .put("decided_by", decidedBy)
        .put("decided_at", decidedAt)
        .put("reason", reason.take(600))
        .apply { messageRef?.let { put("ref", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): GroupPostIndexEntry? = runCatching {
            val state = GroupPostState.valueOf(o.getString("state"))
            GroupPostIndexEntry(
                postId = o.getString("post_id"),
                conversationId = o.optString("conversation_id"),
                postHash = o.getString("hash"),
                state = state,
                messageRef = if (state == GroupPostState.Visible) {
                    o.optJSONObject("ref")?.let(WeaveObjectRef::fromJson)
                } else null,
                decidedBy = o.optString("decided_by"),
                decidedAt = o.optLong("decided_at"),
                reason = o.optString("reason"),
            )
        }.getOrNull()
    }
}

/**
 * Durable event journal entry. Content refs are intentionally absent. That way a later Remove
 * actually removes the branch's advertised link instead of leaving a recoverable link in an old
 * journal event. The temporary delivery envelope below may carry a ref while the event is pending.
 */
data class GroupEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val groupId: String,
    val branchId: String,
    val kind: GroupEventKind,
    val actorMainDht: String,
    val createdAt: Long,
    val objectId: String = "",
    val conversationId: String = "",
    val objectHash: String = "",
    val reason: String = "",
    val metadata: Map<String, String> = emptyMap(),
) {
    private fun unsignedJson(): JSONObject = JSONObject()
        .put("v", 2)
        .put("event_id", eventId)
        .put("group_id", groupId)
        .put("branch_id", branchId)
        .put("kind", kind.name)
        .put("actor", actorMainDht)
        .put("created_at", createdAt)
        .put("object_id", objectId)
        .put("conversation_id", conversationId)
        .put("object_hash", objectHash)
        .put("reason", reason.take(600))
        .put("metadata", JSONObject().apply { metadata.toSortedMap().forEach { (k, v) -> put(k, v.take(1000)) } })

    fun digestHex(): String = groupSha256Hex(unsignedJson().toString().encodeToByteArray())

    fun toJson(): JSONObject = unsignedJson().put("digest", digestHex())
    fun toBytes(): ByteArray = toJson().toString().encodeToByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): GroupEvent? = runCatching {
            val o = JSONObject(bytes.decodeToString())
            if (o.optInt("v") != 2) return null
            val metaObj = o.optJSONObject("metadata") ?: JSONObject()
            val metadata = buildMap {
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, metaObj.optString(key))
                }
            }
            val event = GroupEvent(
                eventId = o.getString("event_id"),
                groupId = o.getString("group_id"),
                branchId = o.getString("branch_id"),
                kind = GroupEventKind.valueOf(o.getString("kind")),
                actorMainDht = o.getString("actor"),
                createdAt = o.getLong("created_at"),
                objectId = o.optString("object_id"),
                conversationId = o.optString("conversation_id"),
                objectHash = o.optString("object_hash"),
                reason = o.optString("reason"),
                metadata = metadata,
            )
            if (!event.digestHex().equals(o.optString("digest"), ignoreCase = true)) null else event
        }.getOrNull()
    }
}

/**
 * Short-lived transport envelope. A ref is allowed here because this is the pending/intake path,
 * not the permanent branch journal. Open groups may expose this through spectator-readable
 * ServiceRequest mailboxes; private authority traffic uses authenticated encrypted messaging.
 */
data class GroupWireEnvelope(
    val event: GroupEvent,
    val contentRef: WeaveObjectRef? = null,
    val targetBranchOwner: String = "",
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("type", TYPE)
        .put("event", event.toJson())
        .put("target_owner", targetBranchOwner)
        .apply { contentRef?.let { put("content_ref", it.toJson()) } }
        .toString()
        .encodeToByteArray()

    companion object {
        const val TYPE = "weave.group.event.v2"
        fun fromBytes(bytes: ByteArray): GroupWireEnvelope? = runCatching {
            val o = JSONObject(bytes.decodeToString())
            if (o.optString("type") != TYPE) return null
            val eventBytes = o.getJSONObject("event").toString().encodeToByteArray()
            val event = GroupEvent.fromBytes(eventBytes) ?: return null
            GroupWireEnvelope(
                event = event,
                contentRef = o.optJSONObject("content_ref")?.let(WeaveObjectRef::fromJson),
                targetBranchOwner = o.optString("target_owner"),
            )
        }.getOrNull()
    }
}


data class GroupPendingItem(
    val event: GroupEvent,
    val contentRef: WeaveObjectRef? = null,
    val sourceMainDht: String = "",
    val publicSpectator: Boolean = false,
    val encryptedSpectator: Boolean = false,
    val expiresAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("event", event.toJson())
        .put("source", sourceMainDht)
        .put("public", publicSpectator)
        .put("encrypted_spectator", encryptedSpectator)
        .put("expires_at", expiresAt)
        .apply { contentRef?.let { put("ref", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): GroupPendingItem? = runCatching {
            val event = GroupEvent.fromBytes(o.getJSONObject("event").toString().encodeToByteArray()) ?: return null
            GroupPendingItem(
                event = event,
                contentRef = o.optJSONObject("ref")?.let(WeaveObjectRef::fromJson),
                sourceMainDht = o.optString("source"),
                publicSpectator = o.optBoolean("public"),
                encryptedSpectator = o.optBoolean("encrypted_spectator"),
                expiresAt = o.optLong("expires_at"),
            )
        }.getOrNull()
    }
}

data class GroupBranchHeader(
    val group: GroupRecord,
    val branchId: String,
    val kind: GroupBranchKind,
    val ownerMainDht: String,
    val creatorRoot: String,
    val branchRoot: String,
    val eventRoot: String,
    val indexRoot: String,
    val generation: Long,
    val moderators: List<GroupModeratorGrant> = emptyList(),
    val knownBranches: List<GroupBranchPointer> = emptyList(),
) {
    fun pointer(): GroupBranchPointer = GroupBranchPointer(
        groupId = group.groupId,
        branchId = branchId,
        branchRoot = branchRoot,
        ownerMainDht = ownerMainDht,
        kind = kind,
        creatorRoot = creatorRoot,
        updatedAt = group.updatedAt,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("v", 2)
        .put("group", group.toPublicJson())
        .put("branch_id", branchId)
        .put("kind", kind.name)
        .put("owner", ownerMainDht)
        .put("creator_root", creatorRoot)
        .put("branch_root", branchRoot)
        .put("event_root", eventRoot)
        .put("index_root", indexRoot)
        .put("generation", generation)
        .put("moderators", JSONArray().apply { moderators.forEach { put(it.toJson()) } })
        .put("known_branches", JSONArray().apply { knownBranches.distinctBy { it.branchId }.take(64).forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): GroupBranchHeader? = runCatching {
            if (o.optInt("v") != 2) return null
            val moderators = buildList {
                val a = o.optJSONArray("moderators") ?: JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let(GroupModeratorGrant::fromJson)?.let(::add)
            }
            val branches = buildList {
                val a = o.optJSONArray("known_branches") ?: JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let(GroupBranchPointer::fromJson)?.let(::add)
            }
            GroupBranchHeader(
                group = GroupRecord.fromJson(o.getJSONObject("group")),
                branchId = o.getString("branch_id"),
                kind = GroupBranchKind.valueOf(o.getString("kind")),
                ownerMainDht = o.getString("owner"),
                creatorRoot = o.getString("creator_root"),
                branchRoot = o.getString("branch_root"),
                eventRoot = o.getString("event_root"),
                indexRoot = o.getString("index_root"),
                generation = o.optLong("generation"),
                moderators = moderators,
                knownBranches = branches,
            )
        }.getOrNull()
    }
}

fun weaveMessageDigest(message: WeaveMessage): String =
    groupSha256Hex(message.toJson().toString().encodeToByteArray())

fun groupSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
