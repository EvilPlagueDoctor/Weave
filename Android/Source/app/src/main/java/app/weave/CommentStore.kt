package app.weave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * NOT NETWORKED YET.
 *
 * This is a local, on-device store so the viewer UI is real and exercisable. Comments never
 * leave the device. When the DHT comment layer lands, replace the body of [LocalCommentStore]
 * and the UI above it should not need to change.
 *
 * Design note carried over from the architecture discussion: comments are keyed per PAGE,
 * not per profile, so a comment attaches to the specific project it is about.
 */
/**
 * Where a comment is in the owner's decision, not where it is stored.
 *
 * [Provisional] exists because of open mode: a comment published as a service request is
 * already readable by anyone before the owner has done anything about it. Modelling those as
 * "quarantined" would be a lie — they are public, they are just not endorsed, and they expire
 * on their own if the owner never accepts them.
 */
enum class CommentState {
    /** Written locally, network write not confirmed yet. Only ever your own comments. */
    Sending,

    /** The network write failed. Yours, still on this device, not published. */
    Failed,

    Accepted,
    Provisional,
    Held,
    Dropped,
}

/** How a comment reached the page, which determines who could already read it. */
enum class CommentOrigin {
    /** Sent to the owner directly. Nobody else can see it until the owner republishes it. */
    Direct,

    /** Published as an open service request. Readable by anyone, expires on its TTL. */
    Open,
}

data class Comment(
    val id: String,
    val pageKey: String,
    val authorKey: String,
    val authorName: String,
    val body: String,
    val createdAt: Long,
    val state: CommentState,
    val origin: CommentOrigin = CommentOrigin.Direct,
    /** Open-mode only: when the underlying request lapses. Zero means no expiry. */
    val expiresAt: Long = 0L,
    /** Id of the comment this replies to, or null for a top-level comment. */
    val replyTo: String? = null,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0L && now >= expiresAt && state != CommentState.Accepted

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("page_key", pageKey).put("author_key", authorKey)
        .put("author_name", authorName).put("body", body)
        .put("created_at", createdAt).put("state", state.name)
        .put("origin", origin.name).put("expires_at", expiresAt)
        .apply { replyTo?.let { put("reply_to", it) } }

    companion object {
        fun fromJson(o: JSONObject) = Comment(
            id = o.getString("id"),
            pageKey = o.getString("page_key"),
            authorKey = o.optString("author_key"),
            authorName = o.optString("author_name", "Someone"),
            body = o.optString("body"),
            createdAt = o.optLong("created_at"),
            state = runCatching { CommentState.valueOf(o.optString("state")) }.getOrDefault(CommentState.Held),
            origin = runCatching { CommentOrigin.valueOf(o.optString("origin")) }.getOrDefault(CommentOrigin.Direct),
            expiresAt = o.optLong("expires_at"),
            replyTo = o.optString("reply_to").takeIf { it.isNotBlank() },
        )
    }
}

/** Stable key for "this page of this profile". */
fun pageKeyOf(mainDht: String, pageId: String): String = "$mainDht#$pageId"

/** Whose page a comment was left on. Moderation authority belongs to this key, not the author's. */
fun ownerKeyOf(pageKey: String): String = pageKey.substringBefore('#')

data class PendingCommentNotice(
    val sender: String,
    val notice: CommentNotice,
    val receivedAt: Long = System.currentTimeMillis(),
)

interface CommentStore {
    /** Accepted comments plus, in open mode, unexpired provisional ones. */
    fun forPage(pageKey: String): List<Comment>
    fun add(
        pageKey: String,
        authorKey: String,
        authorName: String,
        body: String,
        state: CommentState,
        origin: CommentOrigin,
        expiresAt: Long = 0L,
    ): Comment
    fun setState(id: String, state: CommentState)
    /** Comments held for the owner of [ownerKey]'s pages to decide on. Only the page owner moderates. */
    fun quarantined(ownerKey: String): List<Comment>
    /** Comments [authorKey] wrote that are still waiting on someone else's approval. */
    fun awaitingApproval(authorKey: String): List<Comment>
    /** Incoming comment history for pages owned by this identity, newest first. */
    fun activityForOwner(ownerKey: String): List<Comment>
    /** Comments the local user has hidden for themselves. Hidden for you, still visible to others. */
    fun hideForMe(id: String)

    /**
     * Inserts or updates a comment that came from the network, keyed on its id so a comment
     * fetched twice does not appear twice. A locally dropped comment stays dropped.
     */
    fun upsert(comment: Comment)

    /** Everything on a page regardless of state, for reconciling against a fetch. */
    fun allForPage(pageKey: String): List<Comment>

    /** Where the pointer for a comment lives, so the owner can add it to their index. */
    fun rememberPointer(id: String, recordKey: String, subkey: Int)

    fun pointerFor(id: String): Pair<String, Int>?

    fun byId(id: String): Comment?

    /** Comments on this page the local user chose to hide. Hidden for them, not for anyone else. */
    fun hiddenForPage(pageKey: String): List<Comment>

    /** Reverses [hideForMe]. */
    fun unhide(id: String)

    /** Durable queue for direct notices whose DHT body was not readable yet. */
    fun rememberPendingNotice(sender: String, notice: CommentNotice)
    fun pendingNotices(): List<PendingCommentNotice>
    fun forgetPendingNotice(commentId: String)
}

class LocalCommentStore(context: Context) : CommentStore, PrivateVault.Participant {
    private val legacyFile = File(context.filesDir, "comments.json")
    private val vault = PrivateVault.get(context)
    private val vaultKey = "comments/state.json"
    private val items = mutableListOf<Comment>()
    private val hiddenLocally = mutableSetOf<String>()

    /** comment id -> (record key, subkey). Needed to build an index entry when keeping one. */
    private val pointers = mutableMapOf<String, Pair<String, Int>>()
    private val pending = mutableMapOf<String, PendingCommentNotice>()

    init { vault.register(this) }

    override fun onVaultAttached() {
        runCatching {
            vault.migrateLegacyFile(vaultKey, legacyFile)
            reloadFromVault()
        }
    }

    @Synchronized
    override fun onVaultDetached() {
        items.clear(); hiddenLocally.clear(); pointers.clear(); pending.clear()
    }

    @Synchronized
    private fun reloadFromVault() {
        val text = vault.getText(vaultKey) ?: return
        val root = JSONObject(text)
        items.clear(); hiddenLocally.clear(); pointers.clear(); pending.clear()
            val arr = root.optJSONArray("comments") ?: JSONArray()
            for (i in 0 until arr.length()) items.add(Comment.fromJson(arr.getJSONObject(i)))
            val hidden = root.optJSONArray("hidden") ?: JSONArray()
            for (i in 0 until hidden.length()) hiddenLocally.add(hidden.getString(i))
            val saved = root.optJSONObject("pointers") ?: JSONObject()
            saved.keys().forEach { key ->
                val entry = saved.optJSONObject(key) ?: return@forEach
                pointers[key] = entry.optString("store") to entry.optInt("i")
            }
            val pendingArr = root.optJSONArray("pending_notices") ?: JSONArray()
            for (i in 0 until pendingArr.length()) {
                val entry = pendingArr.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                val sender = entry.optString("sender")
                val store = entry.optString("store")
                val subkey = entry.optInt("subkey")
                val page = entry.optString("page")
                if (id.isBlank() || sender.isBlank() || store.isBlank() || page.isBlank()) continue
                pending[id] = PendingCommentNotice(
                    sender = sender,
                    notice = CommentNotice(page, CommentChain.Pointer(store, subkey), id),
                    receivedAt = entry.optLong("received_at", System.currentTimeMillis()),
                )
            }
    }

    @Synchronized
    private fun persist() {
        if (!vault.attached) return
        runCatching {
            val savedPointers = JSONObject()
            pointers.forEach { (id, location) ->
                savedPointers.put(id, JSONObject().put("store", location.first).put("i", location.second))
            }
            val pendingArr = JSONArray()
            pending.values.forEach { item ->
                pendingArr.put(
                    JSONObject()
                        .put("id", item.notice.commentId)
                        .put("sender", item.sender)
                        .put("page", item.notice.pageKey)
                        .put("store", item.notice.pointer.recordKey)
                        .put("subkey", item.notice.pointer.subkey)
                        .put("received_at", item.receivedAt)
                )
            }
            val root = JSONObject()
                .put("comments", JSONArray(items.map { it.toJson() }))
                .put("hidden", JSONArray(hiddenLocally.toList()))
                .put("pointers", savedPointers)
                .put("pending_notices", pendingArr)
            vault.putText(vaultKey, root.toString())
        }
    }

    @Synchronized
    override fun forPage(pageKey: String): List<Comment> {
        val now = System.currentTimeMillis()
        return items
            .filter { it.pageKey == pageKey && it.id !in hiddenLocally && !it.isExpired(now) }
            // Sending and Failed are shown too: they are your own words, and hiding them
            // until the network confirms makes it look like the comment vanished.
            .filter {
                it.state == CommentState.Accepted || it.state == CommentState.Provisional ||
                    it.state == CommentState.Sending || it.state == CommentState.Failed
            }
            .sortedBy { it.createdAt }
    }

    @Synchronized
    override fun add(
        pageKey: String,
        authorKey: String,
        authorName: String,
        body: String,
        state: CommentState,
        origin: CommentOrigin,
        expiresAt: Long,
    ): Comment {
        val comment = Comment(
            id = makeId("comment"), pageKey = pageKey, authorKey = authorKey,
            authorName = authorName, body = body.trim().take(MAX_COMMENT_CHARS),
            createdAt = System.currentTimeMillis(), state = state,
            origin = origin, expiresAt = expiresAt,
        )
        items.add(comment)
        persist()
        return comment
    }

    @Synchronized
    override fun setState(id: String, state: CommentState) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items[index] = items[index].copy(state = state)
            persist()
        }
    }

    @Synchronized
    override fun quarantined(ownerKey: String): List<Comment> {
        val now = System.currentTimeMillis()
        return items.filter {
            ownerKeyOf(it.pageKey) == ownerKey && !it.isExpired(now) &&
                it.state == CommentState.Held
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    override fun awaitingApproval(authorKey: String): List<Comment> {
        val now = System.currentTimeMillis()
        return items.filter {
            it.authorKey == authorKey && !it.isExpired(now) && it.state == CommentState.Held
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    override fun activityForOwner(ownerKey: String): List<Comment> {
        val now = System.currentTimeMillis()
        return items.filter {
            ownerKeyOf(it.pageKey) == ownerKey && it.authorKey != ownerKey && !it.isExpired(now)
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    override fun hideForMe(id: String) {
        hiddenLocally.add(id)
        persist()
    }

    @Synchronized
    override fun upsert(comment: Comment) {
        val index = items.indexOfFirst { it.id == comment.id }
        if (index < 0) {
            items.add(comment)
        } else {
            val existing = items[index]
            // A local decision outranks anything the network says about the same comment:
            // dropping something and having it reappear on the next sync would make
            // moderation feel broken.
            if (existing.state == CommentState.Dropped) return
            // A confirmed state from the network always replaces a local Sending/Failed.
            items[index] = comment.copy(
                state = if (existing.state == CommentState.Accepted) CommentState.Accepted else comment.state
            )
        }
        persist()
    }

    @Synchronized
    override fun allForPage(pageKey: String): List<Comment> =
        items.filter { it.pageKey == pageKey }.sortedBy { it.createdAt }

    @Synchronized
    override fun rememberPointer(id: String, recordKey: String, subkey: Int) {
        pointers[id] = recordKey to subkey
        persist()
    }

    @Synchronized
    override fun pointerFor(id: String): Pair<String, Int>? = pointers[id]

    @Synchronized
    override fun byId(id: String): Comment? = items.firstOrNull { it.id == id }

    @Synchronized
    override fun hiddenForPage(pageKey: String): List<Comment> {
        val now = System.currentTimeMillis()
        return items.filter { it.pageKey == pageKey && it.id in hiddenLocally && !it.isExpired(now) }
            .sortedBy { it.createdAt }
    }

    @Synchronized
    override fun unhide(id: String) {
        if (hiddenLocally.remove(id)) persist()
    }
    @Synchronized
    override fun rememberPendingNotice(sender: String, notice: CommentNotice) {
        if (sender.isBlank() || notice.commentId.isBlank()) return
        pending[notice.commentId] = PendingCommentNotice(sender, notice)
        persist()
    }

    @Synchronized
    override fun pendingNotices(): List<PendingCommentNotice> = pending.values.sortedBy { it.receivedAt }

    @Synchronized
    override fun forgetPendingNotice(commentId: String) {
        if (pending.remove(commentId) != null) persist()
    }
}

/**
 * Light moderation, as decided: hold duplicates and comments from profiles with no history.
 * This is intentionally crude — it is a placeholder for the real classifier, and it errs
 * toward holding rather than publishing, because a held comment is recoverable and a
 * published one is not.
 */
const val MAX_COMMENT_CHARS = 4000

/** Anything past this is folded behind a "show more" rather than dropped. */
const val COMMENT_TRUNCATE_CHARS = 600

/**
 * Light moderation, as specified: hold repetition, not strangers.
 *
 * The earlier version quarantined anyone the reader did not already follow, which on a young
 * network is everyone — every comment would have been held and the feature would have looked
 * broken. Being new is not evidence of anything.
 *
 * [openMode] decides what an unaccepted comment becomes. In open mode it is already public,
 * so it becomes [CommentState.Provisional] and shows with a marker. In closed mode only the
 * owner has it, so it is [CommentState.Held] until they release it.
 */
fun triageComment(
    body: String,
    authorKey: String,
    pageOwnerKey: String,
    existing: List<Comment>,
    openMode: Boolean,
): CommentState {
    // You never moderate yourself on your own page.
    if (authorKey.isNotBlank() && authorKey == pageOwnerKey) return CommentState.Accepted

    val normalized = body.trim().lowercase()
    val unaccepted = if (openMode) CommentState.Provisional else CommentState.Held
    if (normalized.isEmpty()) return CommentState.Dropped

    val duplicateOnPage = existing.any { it.body.trim().lowercase() == normalized }
    val repeatingAuthor = existing.count { it.authorKey == authorKey && it.authorKey.isNotBlank() } >= 5
    return if (duplicateOnPage || repeatingAuthor) unaccepted else CommentState.Accepted
}

/**
 * Open-mode comments are readable by anyone before the owner sees them, so the reader's own
 * client trims them. Truncation is display-only — the full text is kept.
 */
fun displayBody(comment: Comment, expanded: Boolean): String =
    if (expanded || comment.body.length <= COMMENT_TRUNCATE_CHARS) comment.body
    else comment.body.take(COMMENT_TRUNCATE_CHARS).trimEnd() + "\u2026"

/** Who the local user follows. A filter over discovery, not a social graph published anywhere. */
class FollowStore(context: Context) : PrivateVault.Participant {
    private val legacyFile = File(context.filesDir, "following.json")
    private val vault = PrivateVault.get(context)
    private val vaultKey = "following/state.json"
    private val keys = mutableSetOf<String>()

    init { vault.register(this) }

    override fun onVaultAttached() {
        runCatching {
            vault.migrateLegacyFile(vaultKey, legacyFile)
            keys.clear()
            val arr = JSONArray(vault.getText(vaultKey) ?: "[]")
            for (i in 0 until arr.length()) keys.add(arr.getString(i))
        }
    }

    override fun onVaultDetached() { keys.clear() }

    fun isFollowing(mainDht: String): Boolean = mainDht in keys

    fun toggle(mainDht: String): Boolean {
        val nowFollowing = if (mainDht in keys) { keys.remove(mainDht); false } else { keys.add(mainDht); true }
        if (vault.attached) runCatching { vault.putText(vaultKey, JSONArray(keys.toList()).toString()) }
        return nowFollowing
    }

    fun all(): Set<String> = keys.toSet()
}
