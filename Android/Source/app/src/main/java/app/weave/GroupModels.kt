package app.weave

import org.json.JSONArray
import org.json.JSONObject

enum class GroupJoinPolicy { Open, Request, Invite }
enum class GroupPostingPolicy { Everyone, Established, Approval }
enum class GroupModerationPreset { Relaxed, Balanced, Strict, Custom }
enum class GroupVisibility { Public, Unlisted, MembersOnly }
enum class GroupRole { Creator, Claimer, Admin, Moderator, Member }
enum class FeaturedKind { None, Post, Widget, Message }
enum class GroupModerationKind { Report, JoinRequest, Appeal, QuarantinedPost }
enum class GroupModerationState { Pending, Kept, Dropped, Approved, Rejected }

const val MAX_GROUP_THUMBNAIL_BASE64_CHARS = 20_000

private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback

data class GroupPolicy(
    val join: GroupJoinPolicy = GroupJoinPolicy.Open,
    val posting: GroupPostingPolicy = GroupPostingPolicy.Everyone,
    val moderation: GroupModerationPreset = GroupModerationPreset.Balanced,
    val visibility: GroupVisibility = GroupVisibility.Public,
    val quarantineNewMembers: Boolean = false,
    val duplicateProtection: Boolean = true,
    val excessivePostingProtection: Boolean = true,
    val collapseReportCount: Int = 3,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("join", join.name)
        .put("posting", posting.name)
        .put("moderation", moderation.name)
        .put("visibility", visibility.name)
        .put("quarantine_new", quarantineNewMembers)
        .put("duplicate_protection", duplicateProtection)
        .put("rate_protection", excessivePostingProtection)
        .put("collapse_reports", collapseReportCount)

    companion object {
        fun fromJson(o: JSONObject?): GroupPolicy {
            if (o == null) return GroupPolicy()
            return GroupPolicy(
                join = enumOr(o.optString("join"), GroupJoinPolicy.Open),
                posting = enumOr(o.optString("posting"), GroupPostingPolicy.Everyone),
                moderation = enumOr(o.optString("moderation"), GroupModerationPreset.Balanced),
                visibility = enumOr(o.optString("visibility"), GroupVisibility.Public),
                quarantineNewMembers = o.optBoolean("quarantine_new"),
                duplicateProtection = o.optBoolean("duplicate_protection", true),
                excessivePostingProtection = o.optBoolean("rate_protection", true),
                collapseReportCount = o.optInt("collapse_reports", 3).coerceIn(1, 100),
            )
        }
    }
}

data class FeaturedSlot(
    val kind: FeaturedKind = FeaturedKind.None,
    val ref: WeaveObjectRef? = null,
    val title: String = "",
    val body: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("title", title.take(120))
        .put("body", body.take(1200))
        .apply { ref?.let { put("ref", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject?): FeaturedSlot {
            if (o == null) return FeaturedSlot()
            return FeaturedSlot(
                kind = enumOr(o.optString("kind"), FeaturedKind.None),
                ref = o.optJSONObject("ref")?.let(WeaveObjectRef::fromJson),
                title = o.optString("title").take(120),
                body = o.optString("body").take(1200),
            )
        }
    }
}

data class GroupMessageView(
    val message: WeaveMessage,
    val ref: WeaveObjectRef,
    val pinned: Boolean = false,
)

data class GroupPostDetail(
    val groupId: String,
    val branchId: String,
    val conversationId: String,
    val root: GroupMessageView,
    val comments: List<GroupMessageView>,
)

data class GroupConversationPreview(
    val conversation: WeaveObjectRef,
    val title: String,
    val lastActivity: Long,
    val rootMessage: MessagePreview? = null,
    val recentMessages: List<MessagePreview> = emptyList(),
    val replyCount: Int = 0,
    val recentUniqueAuthors: Int = 0,
    val approximateParticipants: Int = 0,
    val activityScore: Double = 0.0,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val moderationLabel: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("conversation", conversation.toJson())
        .put("title", title.take(160))
        .put("last_activity", lastActivity)
        .apply { rootMessage?.let { put("root_message", it.toJson()) } }
        .put("reply_count", replyCount)
        .put("unique_authors", recentUniqueAuthors)
        .put("participants", approximateParticipants)
        .put("score", activityScore)
        .put("pinned", pinned)
        .put("locked", locked)
        .put("moderation", moderationLabel.take(80))
        .put("messages", JSONArray().apply { recentMessages.take(3).forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): GroupConversationPreview {
            val messages = buildList {
                val a = o.optJSONArray("messages")
                if (a != null) for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { add(MessagePreview.fromJson(it)) }
                }
            }
            return GroupConversationPreview(
                conversation = WeaveObjectRef.fromJson(o.getJSONObject("conversation")),
                title = o.optString("title"),
                lastActivity = o.optLong("last_activity"),
                rootMessage = o.optJSONObject("root_message")?.let(MessagePreview::fromJson),
                recentMessages = messages,
                replyCount = o.optInt("reply_count", messages.size),
                recentUniqueAuthors = o.optInt("unique_authors"),
                approximateParticipants = o.optInt("participants"),
                activityScore = o.optDouble("score"),
                pinned = o.optBoolean("pinned"),
                locked = o.optBoolean("locked"),
                moderationLabel = o.optString("moderation"),
            )
        }
    }
}

data class GroupPulse(
    val groupId: String,
    val generation: Long = 0,
    val updatedAt: Long = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val conversations: List<GroupConversationPreview> = emptyList(),
    val removedPosts: List<GroupRemovedPostNotice> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("group", groupId)
        .put("generation", generation)
        .put("updated_at", updatedAt)
        .put("page", page)
        .put("page_count", pageCount)
        .put("conversations", JSONArray().apply {
            conversations.take(MAX_PULSE_CONVERSATIONS).forEach { put(it.toJson()) }
        })
        .put("removed_posts", JSONArray().apply {
            removedPosts.take(MAX_REMOVED_POST_NOTICES).forEach { put(it.toJson()) }
        })

    companion object {
        const val MAX_PULSE_CONVERSATIONS = 24

        fun fromJson(o: JSONObject): GroupPulse {
            val conversations = buildList {
                val a = o.optJSONArray("conversations")
                if (a != null) for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { add(GroupConversationPreview.fromJson(it)) }
                }
            }
            val removed = buildList {
                val a = o.optJSONArray("removed_posts")
                if (a != null) for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let(GroupRemovedPostNotice::fromJson)?.let(::add)
                }
            }
            return GroupPulse(
                groupId = o.optString("group"),
                generation = o.optLong("generation"),
                updatedAt = o.optLong("updated_at"),
                page = o.optInt("page"),
                pageCount = o.optInt("page_count", 1).coerceAtLeast(1),
                conversations = conversations,
                removedPosts = removed,
            )
        }

        const val MAX_REMOVED_POST_NOTICES = 24
    }
}

data class GroupRemovedPostNotice(
    val postId: String,
    val conversationId: String,
    val title: String,
    val authorName: String,
    val reason: String,
    val removedBy: String,
    val removedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("post_id", postId)
        .put("conversation_id", conversationId)
        .put("title", title.take(160))
        .put("author_name", authorName.take(80))
        .put("reason", reason.take(600))
        .put("removed_by", removedBy)
        .put("removed_at", removedAt)

    companion object {
        fun fromJson(o: JSONObject): GroupRemovedPostNotice? = runCatching {
            GroupRemovedPostNotice(
                postId = o.getString("post_id"),
                conversationId = o.optString("conversation_id"),
                title = o.optString("title").take(160),
                authorName = o.optString("author_name").take(80),
                reason = o.optString("reason").take(600),
                removedBy = o.optString("removed_by"),
                removedAt = o.optLong("removed_at"),
            )
        }.getOrNull()
    }
}

/** A readable view of a still-retained Groups-v2 submission in the local curator/custody cache. */
data class GroupCuratorPost(
    val eventId: String,
    val conversationId: String,
    val authorMainDht: String,
    val createdAt: Long,
    val status: String,
    val message: WeaveMessage?,
)

data class GroupRecord(
    val groupId: String,
    val rootRecordKey: String = "",
    val ownerId: String,
    val name: String,
    val description: String = "",
    /** Tiny embedded WebP preview used to visually distinguish groups without loading a blob. */
    val thumbnailBase64: String? = null,
    val tags: List<String> = emptyList(),
    val policy: GroupPolicy = GroupPolicy(),
    val featured: FeaturedSlot = FeaturedSlot(),
    val role: GroupRole = GroupRole.Member,
    val joined: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val pulseGeneration: Long = 0,
    val approximateMembers: Int = 1,
) {
    fun toPublicJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("group_id", groupId)
        .put("root", rootRecordKey)
        .put("owner", ownerId)
        .put("name", name.take(120))
        .put("description", description.take(2000))
        .apply {
            thumbnailBase64?.takeIf { it.isNotBlank() && it.length <= MAX_GROUP_THUMBNAIL_BASE64_CHARS }
                ?.let { put("thumbnail", it) }
        }
        .put("tags", JSONArray(tags.distinct().take(32)))
        .put("policy", policy.toJson())
        .put("featured", featured.toJson())
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("pulse_generation", pulseGeneration)
        .put("members", approximateMembers)

    fun toLocalJson(): JSONObject = toPublicJson()
        .put("role", role.name)
        .put("joined", joined)

    companion object {
        fun fromJson(o: JSONObject): GroupRecord {
            val tags = buildList {
                val a = o.optJSONArray("tags")
                if (a != null) for (i in 0 until a.length()) {
                    a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            return GroupRecord(
                groupId = o.getString("group_id"),
                rootRecordKey = o.optString("root"),
                ownerId = o.optString("owner"),
                name = o.optString("name", "Unnamed group"),
                description = o.optString("description"),
                thumbnailBase64 = o.optString("thumbnail")
                    .takeIf { it.isNotBlank() && it.length <= MAX_GROUP_THUMBNAIL_BASE64_CHARS },
                tags = tags,
                policy = GroupPolicy.fromJson(o.optJSONObject("policy")),
                featured = FeaturedSlot.fromJson(o.optJSONObject("featured")),
                role = enumOr(o.optString("role"), GroupRole.Member),
                joined = o.optBoolean("joined", false),
                createdAt = o.optLong("created_at"),
                updatedAt = o.optLong("updated_at"),
                pulseGeneration = o.optLong("pulse_generation"),
                approximateMembers = o.optInt("members", 1).coerceAtLeast(0),
            )
        }
    }
}

data class GroupModerationTask(
    val taskId: String,
    val groupId: String,
    val kind: GroupModerationKind,
    val objectRef: WeaveObjectRef? = null,
    val reporterId: String = "",
    val reason: String = "",
    val createdAt: Long,
    val state: GroupModerationState = GroupModerationState.Pending,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", taskId)
        .put("group", groupId)
        .put("kind", kind.name)
        .put("reporter", reporterId)
        .put("reason", reason.take(600))
        .put("created_at", createdAt)
        .put("state", state.name)
        .apply { objectRef?.let { put("ref", it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject): GroupModerationTask = GroupModerationTask(
            taskId = o.getString("id"),
            groupId = o.optString("group"),
            kind = enumOr(o.optString("kind"), GroupModerationKind.Report),
            objectRef = o.optJSONObject("ref")?.let(WeaveObjectRef::fromJson),
            reporterId = o.optString("reporter"),
            reason = o.optString("reason"),
            createdAt = o.optLong("created_at"),
            state = enumOr(o.optString("state"), GroupModerationState.Pending),
        )
    }
}
