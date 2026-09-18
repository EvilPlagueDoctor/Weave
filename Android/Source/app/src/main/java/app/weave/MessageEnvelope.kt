package app.weave

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

enum class WeaveObjectType {
    Message, Conversation, Image, Audio, Widget, Profile, Group;

    companion object {
        fun fromWire(value: String): WeaveObjectType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Message
    }
}

/**
 * Stable logical identity plus the physical DHT location that currently resolves it.
 *
 * objectId answers "what is this?" while recordKey/subkey answer "where do I fetch it?".
 * Keeping both means later compaction/migration does not force UI code to use a DHT address as
 * the permanent identity of a post.
 */
data class WeaveObjectRef(
    val objectId: String,
    val recordKey: String = "",
    val subkey: Int = 0,
    val type: WeaveObjectType,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", objectId)
        .put("record", recordKey)
        .put("subkey", subkey)
        .put("type", type.name.lowercase())

    companion object {
        fun fromJson(o: JSONObject): WeaveObjectRef = WeaveObjectRef(
            objectId = o.optString("id"),
            recordKey = o.optString("record"),
            subkey = o.optInt("subkey"),
            type = WeaveObjectType.fromWire(o.optString("type")),
        )
    }
}

/**
 * Common Weave reply/message envelope.
 *
 * Every reply has the same shape. A text-only reply simply leaves thumbnail/fullMedia empty.
 * The thumbnail bytes travel with the message so a Pulse can draw instantly; full-size content
 * remains in separate DHT blobs and is fetched only after a user asks to open it.
 */
data class WeaveMessage(
    val version: Int = 1,
    val messageId: String,
    val container: WeaveObjectRef,
    val authorId: String,
    val authorName: String,
    val body: String,
    val title: String? = null,
    val thumbnailBase64: String? = null,
    val fullMedia: List<WeaveObjectRef> = emptyList(),
    val replyTo: WeaveObjectRef? = null,
    val root: WeaveObjectRef? = null,
    val createdAt: Long,
    val editedAt: Long? = null,
    /**
     * Branch-local curation never rewrites the author's source object. When a moderator publishes
     * a replacement view for one moderation branch, these fields make that distinction explicit
     * instead of making the replacement look like an edit performed by the original author.
     */
    val curatedBy: String? = null,
    val curatedFromHash: String? = null,
    val flags: Int = 0,
    val signature: String = "",
) {
    fun thumbnailBytes(): ByteArray? = thumbnailBase64
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }

    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("id", messageId)
        .put("container", container.toJson())
        .put("author", authorId)
        .put("name", authorName)
        .put("body", body)
        .put("created_at", createdAt)
        .apply { title?.takeIf { it.isNotBlank() }?.let { put("title", it.take(160)) } }
        .put("flags", flags)
        .apply {
            thumbnailBase64?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
            if (fullMedia.isNotEmpty()) {
                put("media", JSONArray().apply { fullMedia.forEach { put(it.toJson()) } })
            }
            replyTo?.let { put("reply_to", it.toJson()) }
            root?.let { put("root", it.toJson()) }
            editedAt?.let { put("edited_at", it) }
            curatedBy?.takeIf { it.isNotBlank() }?.let { put("curated_by", it) }
            curatedFromHash?.takeIf { it.isNotBlank() }?.let { put("curated_from_hash", it) }
            if (signature.isNotBlank()) put("signature", signature)
        }

    companion object {
        const val MAX_THUMBNAIL_BYTES = 48 * 1024

        fun thumbnailToBase64(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty()) return null
            require(bytes.size <= MAX_THUMBNAIL_BYTES) {
                "Thumbnail exceeds ${MAX_THUMBNAIL_BYTES / 1024} KiB"
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        fun fromJson(o: JSONObject): WeaveMessage {
            val media = buildList {
                val a = o.optJSONArray("media")
                if (a != null) for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { add(WeaveObjectRef.fromJson(it)) }
                }
            }
            return WeaveMessage(
                version = o.optInt("v", 1),
                messageId = o.getString("id"),
                container = WeaveObjectRef.fromJson(o.getJSONObject("container")),
                authorId = o.optString("author"),
                authorName = o.optString("name", "Someone").take(80),
                body = o.optString("body").take(MAX_COMMENT_CHARS),
                title = o.optString("title").takeIf { it.isNotBlank() }?.take(160),
                thumbnailBase64 = o.optString("thumbnail").takeIf { it.isNotBlank() },
                fullMedia = media,
                replyTo = o.optJSONObject("reply_to")?.let(WeaveObjectRef::fromJson),
                root = o.optJSONObject("root")?.let(WeaveObjectRef::fromJson),
                createdAt = o.optLong("created_at"),
                editedAt = o.optLong("edited_at").takeIf { o.has("edited_at") },
                curatedBy = o.optString("curated_by").takeIf { it.isNotBlank() },
                curatedFromHash = o.optString("curated_from_hash").takeIf { it.isNotBlank() },
                flags = o.optInt("flags"),
                signature = o.optString("signature"),
            )
        }
    }
}

/** Small copy used by a Group Pulse; full-size media is never copied into the Pulse. */
data class MessagePreview(
    val messageId: String,
    val authorId: String,
    val authorName: String,
    val bodyPreview: String,
    val title: String? = null,
    val thumbnailBase64: String? = null,
    val fullMessage: WeaveObjectRef? = null,
    val createdAt: Long = 0L,
    val pinned: Boolean = false,
    val hasAudio: Boolean = false,
    val curatedBy: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", messageId)
        .put("author", authorId)
        .put("name", authorName)
        .put("body", bodyPreview.take(320))
        .put("ts", createdAt)
        .apply { title?.takeIf { it.isNotBlank() }?.let { put("title", it.take(160)) } }
        .put("pinned", pinned)
        .put("audio", hasAudio)
        .apply {
            curatedBy?.takeIf { it.isNotBlank() }?.let { put("curated_by", it) }
            thumbnailBase64?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
            fullMessage?.let { put("ref", it.toJson()) }
        }

    companion object {
        fun fromJson(o: JSONObject): MessagePreview = MessagePreview(
            messageId = o.optString("id"),
            authorId = o.optString("author"),
            authorName = o.optString("name", "Someone"),
            bodyPreview = o.optString("body").take(320),
            title = o.optString("title").takeIf { it.isNotBlank() }?.take(160),
            thumbnailBase64 = o.optString("thumbnail").takeIf { it.isNotBlank() },
            fullMessage = o.optJSONObject("ref")?.let(WeaveObjectRef::fromJson),
            createdAt = o.optLong("ts"),
            pinned = o.optBoolean("pinned"),
            hasAudio = o.optBoolean("audio"),
            curatedBy = o.optString("curated_by").takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Compatibility bridge for the currently deployed profile-comment format. It does not rewrite
 * that protocol; it lets group/Pulse code consume the same semantic shape while profile comments
 * are migrated deliberately later.
 */
fun Comment.asWeaveMessage(pointer: Pair<String, Int>? = null): WeaveMessage {
    val pageRef = WeaveObjectRef(
        objectId = pageKey,
        type = WeaveObjectType.Conversation,
    )
    val selfRef = pointer?.let {
        WeaveObjectRef(idForComment(id), it.first, it.second, WeaveObjectType.Message)
    }
    return WeaveMessage(
        messageId = idForComment(id),
        container = pageRef,
        authorId = authorKey,
        authorName = authorName,
        body = body,
        replyTo = replyTo?.let {
            WeaveObjectRef(idForComment(it), type = WeaveObjectType.Message)
        },
        createdAt = createdAt,
        flags = if (selfRef != null) 1 else 0,
    )
}

private fun idForComment(id: String): String = id
