package com.veilysocial.profiledesigner

import android.util.Base64
import org.json.JSONObject

/**
 * A DHT record has a fixed subkey count decided when it is created, so a comment list that
 * grows without bound cannot live in one record. This walks a chain of them: subkey 0 of each
 * link is a header holding the entry count and the record key of the next link, and the
 * remaining subkeys hold entries. Appending past the end of a link creates and links a new one.
 *
 * Only the owner can write, so nothing here needs to handle concurrent appends from two
 * parties. It does have to tolerate a half-finished append — a link created but not yet
 * pointed at, or a header count written before the entry — so readers stop at the count and
 * writers update the header last.
 */
object CommentChain {

    /** Subkeys per link. One header plus [CHAIN_CAPACITY] entries. */
    const val CHAIN_SUBKEYS = 64
    const val CHAIN_CAPACITY = CHAIN_SUBKEYS - 1

    const val COMMENT_STORE_NAME = "veily_comments"
    const val COMMENT_CONTINUATION_NAME = "veily_comments_next"
    const val INDEX_STORE_NAME = "veily_comment_index"
    const val INDEX_CONTINUATION_NAME = "veily_comment_index_next"

    private const val HEADER_SUBKEY = 0

    data class Link(val storeId: String, val recordKey: String)

    data class Header(val next: String, val count: Int) {
        fun toBytes(): ByteArray =
            JSONObject().put("next", next).put("count", count).toString().encodeToByteArray()

        companion object {
            val EMPTY = Header("", 0)
            fun fromJson(json: JSONObject) = Header(json.optString("next"), json.optInt("count"))
        }
    }

    /** Finds an owned store by name, creating and initialising it when absent. */
    fun ensureOwnedStore(client: DaemonClient, name: String): Link {
        val stores = client.listStores().optJSONArray("stores")
        if (stores != null) {
            for (i in 0 until stores.length()) {
                val store = stores.getJSONObject(i)
                if (store.optString("name") == name) {
                    return Link(store.getString("store_id"), store.getString("record_key"))
                }
            }
        }
        val created = client.createStore(name, CHAIN_SUBKEYS).getJSONObject("store")
        val link = Link(created.getString("store_id"), created.getString("record_key"))
        client.writeStore(link.storeId, HEADER_SUBKEY, Header.EMPTY.toBytes())
        return link
    }

    /** Resolves an owned store id from its record key. Continuation links are found this way. */
    private fun ownedStoreIdFor(client: DaemonClient, recordKey: String): String? {
        val stores = client.listStores().optJSONArray("stores") ?: return null
        for (i in 0 until stores.length()) {
            val store = stores.getJSONObject(i)
            if (store.optString("record_key") == recordKey) return store.getString("store_id")
        }
        return null
    }

    private fun readOwnHeader(client: DaemonClient, storeId: String): Header {
        val values = client.readStore(storeId, listOf(HEADER_SUBKEY), false).optJSONArray("values")
        return decodeHeader(values?.optJSONObject(0)) ?: Header.EMPTY
    }

    private fun decodeHeader(value: JSONObject?): Header? {
        val json = decodeValue(value) ?: return null
        return runCatching { Header.fromJson(JSONObject(json.decodeToString())) }.getOrNull()
    }

    fun decodeValue(value: JSONObject?): ByteArray? {
        if (value == null || value.optBoolean("is_null", true)) return null
        val encoded = value.optString("value_base64").takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
    }

    /**
     * Appends one entry, walking to the end of the chain and extending it if the last link is
     * full. Returns where the entry landed, which is what a pointer to it has to carry.
     */
    fun append(
        client: DaemonClient,
        root: Link,
        continuationName: String,
        payload: ByteArray,
    ): Pointer {
        var current = root
        // Bounded so a corrupt cycle cannot spin forever.
        repeat(64) {
            val header = readOwnHeader(client, current.storeId)
            if (header.count < CHAIN_CAPACITY) {
                val subkey = header.count + 1
                client.writeStore(current.storeId, subkey, payload)
                // Header last: a reader that arrives mid-append sees the old count and simply
                // does not yet see this entry, rather than seeing a pointer to nothing.
                client.writeStore(current.storeId, HEADER_SUBKEY, header.copy(count = subkey).toBytes())
                return Pointer(current.recordKey, subkey)
            }
            current = if (header.next.isNotBlank()) {
                val id = ownedStoreIdFor(client, header.next)
                    ?: throw IllegalStateException("comment chain is broken: ${short(header.next)} is not an owned store")
                Link(id, header.next)
            } else {
                val created = client.createStore(continuationName, CHAIN_SUBKEYS).getJSONObject("store")
                val next = Link(created.getString("store_id"), created.getString("record_key"))
                client.writeStore(next.storeId, HEADER_SUBKEY, Header.EMPTY.toBytes())
                client.writeStore(current.storeId, HEADER_SUBKEY, header.copy(next = next.recordKey).toBytes())
                next
            }
        }
        throw IllegalStateException("comment chain is longer than expected; refusing to keep walking")
    }

    /**
     * Reads every entry of a chain owned by someone else.
     *
     * Reads the header on its own first so only the subkeys actually in use are fetched —
     * pulling all 64 regardless would be most of a round trip wasted on a page with three
     * comments. [maxEntries] bounds a hostile or broken chain.
     */
    fun readAll(client: DaemonClient, rootRecordKey: String, maxEntries: Int = 512): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var currentKey = rootRecordKey
        val seen = mutableSetOf<String>()

        while (currentKey.isNotBlank() && out.size < maxEntries) {
            if (!seen.add(currentKey)) break
            val headerValues = client.readPublicStore(currentKey, listOf(HEADER_SUBKEY), true)
                .optJSONArray("values")
            val header = decodeHeader(headerValues?.optJSONObject(0)) ?: break
            val count = header.count.coerceIn(0, CHAIN_CAPACITY)

            if (count > 0) {
                val wanted = (1..count).toList()
                val values = client.readPublicStore(currentKey, wanted, true).optJSONArray("values")
                if (values != null) {
                    for (i in 0 until values.length()) {
                        decodeValue(values.optJSONObject(i))?.let { out.add(it) }
                        if (out.size >= maxEntries) break
                    }
                }
            }
            currentKey = header.next
        }
        return out
    }

    /** Where an entry lives: the record holding it and its subkey. */
    data class Pointer(val recordKey: String, val subkey: Int) {
        fun toJson(): JSONObject = JSONObject().put("store", recordKey).put("i", subkey)

        companion object {
            fun fromJson(json: JSONObject) = Pointer(json.getString("store"), json.getInt("i"))
        }
    }
}

/** A comment as it travels over the wire. Content lives in the author's own record. */
data class WireComment(
    val id: String,
    val pageKey: String,
    val authorKey: String,
    val authorName: String,
    val body: String,
    val createdAt: Long,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("page", pageKey)
        .put("author", authorKey)
        .put("name", authorName)
        .put("body", body)
        .put("ts", createdAt)
        .toString()
        .encodeToByteArray()

    fun toComment(state: CommentState, origin: CommentOrigin, expiresAt: Long = 0L) = Comment(
        id = id,
        pageKey = pageKey,
        authorKey = authorKey,
        authorName = authorName,
        body = body,
        createdAt = createdAt,
        state = state,
        origin = origin,
        expiresAt = expiresAt,
    )

    companion object {
        fun fromBytes(bytes: ByteArray): WireComment? = runCatching {
            val json = JSONObject(bytes.decodeToString())
            WireComment(
                id = json.getString("id"),
                pageKey = json.getString("page"),
                authorKey = json.optString("author"),
                authorName = json.optString("name").ifBlank { "Someone" }.take(60),
                body = json.optString("body").take(MAX_COMMENT_CHARS),
                createdAt = json.optLong("ts"),
            )
        }.getOrNull()
    }
}

/**
 * One entry in a page owner's index: a comment they have kept.
 *
 * The index holds pointers rather than copies, so keeping a comment never duplicates someone
 * else's words into the owner's record, and a commenter who erases their record removes the
 * content even though the pointer survives.
 */
data class IndexEntry(
    val pageKey: String,
    val pointer: CommentChain.Pointer,
    val authorKey: String,
    val commentId: String,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("v", 1)
        .put("page", pageKey)
        .put("ptr", pointer.toJson())
        .put("author", authorKey)
        .put("id", commentId)
        .toString()
        .encodeToByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): IndexEntry? = runCatching {
            val json = JSONObject(bytes.decodeToString())
            IndexEntry(
                pageKey = json.getString("page"),
                pointer = CommentChain.Pointer.fromJson(json.getJSONObject("ptr")),
                authorKey = json.optString("author"),
                commentId = json.optString("id"),
            )
        }.getOrNull()
    }
}

/** Notification sent to a page owner: "there is a comment for you at this pointer". */
data class CommentNotice(
    val pageKey: String,
    val pointer: CommentChain.Pointer,
    val commentId: String,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("type", NOTICE_TYPE)
        .put("page", pageKey)
        .put("ptr", pointer.toJson())
        .put("id", commentId)
        .toString()
        .encodeToByteArray()

    companion object {
        const val NOTICE_TYPE = "veily.comment.v1"

        fun fromBytes(bytes: ByteArray): CommentNotice? = runCatching {
            val json = JSONObject(bytes.decodeToString())
            if (json.optString("type") != NOTICE_TYPE) return null
            CommentNotice(
                pageKey = json.getString("page"),
                pointer = CommentChain.Pointer.fromJson(json.getJSONObject("ptr")),
                commentId = json.optString("id"),
            )
        }.getOrNull()
    }
}
