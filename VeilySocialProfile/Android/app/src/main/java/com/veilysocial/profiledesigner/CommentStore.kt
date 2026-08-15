package com.veilysocial.profiledesigner

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
enum class CommentState { Visible, Quarantined, Hidden }

data class Comment(
    val id: String,
    val pageKey: String,
    val authorKey: String,
    val authorName: String,
    val body: String,
    val createdAt: Long,
    val state: CommentState,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("page_key", pageKey).put("author_key", authorKey)
        .put("author_name", authorName).put("body", body)
        .put("created_at", createdAt).put("state", state.name)

    companion object {
        fun fromJson(o: JSONObject) = Comment(
            id = o.getString("id"),
            pageKey = o.getString("page_key"),
            authorKey = o.optString("author_key"),
            authorName = o.optString("author_name", "Someone"),
            body = o.optString("body"),
            createdAt = o.optLong("created_at"),
            state = runCatching { CommentState.valueOf(o.optString("state")) }.getOrDefault(CommentState.Visible),
        )
    }
}

/** Stable key for "this page of this profile". */
fun pageKeyOf(mainDht: String, pageId: String): String = "$mainDht#$pageId"

interface CommentStore {
    fun forPage(pageKey: String): List<Comment>
    fun add(pageKey: String, authorKey: String, authorName: String, body: String, state: CommentState): Comment
    fun setState(id: String, state: CommentState)
    fun quarantined(): List<Comment>
    /** Comments the local user has hidden for themselves. Hidden for you, still visible to others. */
    fun hideForMe(id: String)
}

class LocalCommentStore(context: Context) : CommentStore {
    private val file = File(context.filesDir, "comments.json")
    private val items = mutableListOf<Comment>()
    private val hiddenLocally = mutableSetOf<String>()

    init {
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                val arr = root.optJSONArray("comments") ?: JSONArray()
                for (i in 0 until arr.length()) items.add(Comment.fromJson(arr.getJSONObject(i)))
                val hidden = root.optJSONArray("hidden") ?: JSONArray()
                for (i in 0 until hidden.length()) hiddenLocally.add(hidden.getString(i))
            }
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
                .put("comments", JSONArray(items.map { it.toJson() }))
                .put("hidden", JSONArray(hiddenLocally.toList()))
            file.writeText(root.toString())
        }
    }

    override fun forPage(pageKey: String): List<Comment> =
        items.filter { it.pageKey == pageKey && it.id !in hiddenLocally && it.state == CommentState.Visible }
            .sortedBy { it.createdAt }

    override fun add(pageKey: String, authorKey: String, authorName: String, body: String, state: CommentState): Comment {
        val comment = Comment(
            id = makeId("comment"), pageKey = pageKey, authorKey = authorKey,
            authorName = authorName, body = body.trim().take(4000),
            createdAt = System.currentTimeMillis(), state = state,
        )
        items.add(comment)
        persist()
        return comment
    }

    override fun setState(id: String, state: CommentState) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items[index] = items[index].copy(state = state)
            persist()
        }
    }

    override fun quarantined(): List<Comment> =
        items.filter { it.state == CommentState.Quarantined }.sortedByDescending { it.createdAt }

    override fun hideForMe(id: String) {
        hiddenLocally.add(id)
        persist()
    }
}

/**
 * Light moderation, as decided: hold duplicates and comments from profiles with no history.
 * This is intentionally crude — it is a placeholder for the real classifier, and it errs
 * toward holding rather than publishing, because a held comment is recoverable and a
 * published one is not.
 */
fun triageComment(body: String, authorKey: String, existing: List<Comment>, knownAuthors: Set<String>): CommentState {
    val normalized = body.trim().lowercase()
    if (normalized.isEmpty()) return CommentState.Quarantined
    val duplicate = existing.any { it.body.trim().lowercase() == normalized }
    val unknownAuthor = authorKey.isNotBlank() && authorKey !in knownAuthors
    return if (duplicate || unknownAuthor) CommentState.Quarantined else CommentState.Visible
}

/** Who the local user follows. A filter over discovery, not a social graph published anywhere. */
class FollowStore(context: Context) {
    private val file = File(context.filesDir, "following.json")
    private val keys = mutableSetOf<String>()

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) keys.add(arr.getString(i))
            }
        }
    }

    fun isFollowing(mainDht: String): Boolean = mainDht in keys

    fun toggle(mainDht: String): Boolean {
        val nowFollowing = if (mainDht in keys) { keys.remove(mainDht); false } else { keys.add(mainDht); true }
        runCatching { file.writeText(JSONArray(keys.toList()).toString()) }
        return nowFollowing
    }

    fun all(): Set<String> = keys.toSet()
}
