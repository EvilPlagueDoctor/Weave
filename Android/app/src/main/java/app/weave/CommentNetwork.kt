package app.weave

import org.json.JSONObject

/** Subkey 1 of a profile store points at that person's comment index. Subkey 0 is the profile record. */
const val COMMENT_INDEX_SUBKEY = 1

/**
 * The comment protocol.
 *
 * Three records are involved and each is owned by exactly one party:
 *
 *  - the **commenter's** chain holds the words they wrote, on their own record;
 *  - the **page owner's** index holds pointers to the comments they have kept;
 *  - the **page owner's** profile store, subkey 1, says where that index lives.
 *
 * Nothing is copied between them. Keeping a comment publishes a pointer, not a duplicate, so
 * an owner can never end up hosting words they did not write, and a commenter who deletes
 * their record removes the content even though the pointer remains.
 *
 * The app root still points at the profile store. An earlier draft registered the comment
 * store as the app root instead, which would have silently replaced the profile pointer every
 * other device uses to find the profile at all.
 */
class CommentNetwork(private val client: DaemonClient) {

    private var myComments: CommentChain.Link? = null
    private var myIndex: CommentChain.Link? = null

    /** Creates the two owned chains if they do not exist. Safe to call repeatedly. */
    fun ensureStores(): CommentChain.Link {
        val comments = myComments ?: CommentChain.ensureOwnedStore(client, CommentChain.COMMENT_STORE_NAME)
            .also { myComments = it }
        myIndex ?: CommentChain.ensureOwnedStore(client, CommentChain.INDEX_STORE_NAME).also { myIndex = it }
        return comments
    }

    /**
     * Publishes the index pointer into the profile store so other people can find it.
     * Called after the profile store exists, since it writes into subkey 1 of that record.
     */
    fun publishIndexPointer(profileStoreId: String) {
        val index = myIndex ?: return
        val payload = JSONObject().put("v", 1).put("index", index.recordKey).toString().encodeToByteArray()
        client.writeStore(profileStoreId, COMMENT_INDEX_SUBKEY, payload)
    }

    /** Reads someone's index location out of their published profile record. */
    fun indexKeyFor(profileRootRecordKey: String): String? = runCatching {
        val values = client.readPublicStore(profileRootRecordKey, listOf(COMMENT_INDEX_SUBKEY), true)
            .optJSONArray("values")
        val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return null
        JSONObject(bytes.decodeToString()).optString("index").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Writes a comment to this device's own chain and tells the page owner where it is.
     *
     * Returns the pointer so the caller can record it locally. Notification failure is not
     * fatal: the comment exists and is readable, the owner simply has not been told, and the
     * daemon will have queued the message through the mailbox if a direct route was not
     * available.
     */
    data class PostResult(
        val pointer: CommentChain.Pointer,
        val notified: Boolean,
        val notificationError: String? = null,
    )

    @Synchronized
    fun post(comment: WireComment, ownerMainDht: String, notify: Boolean): PostResult {
        val root = ensureStores()
        val pointer = CommentChain.append(
            client, root, CommentChain.COMMENT_CONTINUATION_NAME, comment.toBytes()
        )
        if (!notify || ownerMainDht.isBlank() || ownerMainDht == comment.authorKey) {
            return PostResult(pointer, notified = true)
        }
        return runCatching {
            notifyOwner(ownerMainDht, comment.pageKey, pointer, comment.id)
            PostResult(pointer, notified = true)
        }.getOrElse { error ->
            PostResult(pointer, notified = false, notificationError = error.message)
        }
    }

    fun notifyOwner(ownerMainDht: String, pageKey: String, pointer: CommentChain.Pointer, commentId: String) {
        client.sendMessage(
            recipientMainDht = ownerMainDht,
            payload = CommentNotice(pageKey, pointer, commentId).toBytes(),
        )
    }

    fun acknowledge(authorMainDht: String, commentId: String, status: CommentAck.Status) {
        if (authorMainDht.isBlank()) return
        client.sendMessage(
            recipientMainDht = authorMainDht,
            payload = CommentAck(commentId, status).toBytes(),
        )
    }

    /**
     * Adds a pointer to this device's index, which is what makes a comment visible to
     * everyone who reads the page. This is the "Keep" action.
     */
    @Synchronized
    fun keep(entry: IndexEntry) {
        ensureStores()
        val index = myIndex ?: throw IllegalStateException("comment index is not ready")
        CommentChain.append(client, index, CommentChain.INDEX_CONTINUATION_NAME, entry.toBytes())
    }

    /** Every comment the owner of [profileRootRecordKey] has kept on [pageKey]. */
    fun fetchKept(profileRootRecordKey: String, pageKey: String): List<WireComment> {
        val indexKey = indexKeyFor(profileRootRecordKey) ?: return emptyList()
        val entries = CommentChain.readAll(client, indexKey)
            .mapNotNull(IndexEntry::fromBytes)
            .filter { it.pageKey == pageKey }
        if (entries.isEmpty()) return emptyList()

        // Group by record so several comments from one author cost one read, not one each.
        return entries
            .groupBy { it.pointer.recordKey }
            .flatMap { (recordKey, group) -> readComments(recordKey, group) }
            .filter { it.pageKey == pageKey }
    }

    /** Reads one comment, used when a notice arrives pointing at a stranger's record. */
    fun fetchOne(pointer: CommentChain.Pointer): WireComment? = runCatching {
        val values = client.readPublicStore(pointer.recordKey, listOf(pointer.subkey), true)
            .optJSONArray("values")
        CommentChain.decodeValue(values?.optJSONObject(0))?.let(WireComment::fromBytes)
    }.getOrNull()

    private fun readComments(recordKey: String, entries: List<IndexEntry>): List<WireComment> = runCatching {
        val subkeys = entries.map { it.pointer.subkey }.distinct().sorted()
        val values = client.readPublicStore(recordKey, subkeys, true).optJSONArray("values")
            ?: return emptyList()
        val byId = entries.associateBy { it.commentId }
        buildList {
            for (i in 0 until values.length()) {
                val bytes = CommentChain.decodeValue(values.optJSONObject(i)) ?: continue
                val comment = WireComment.fromBytes(bytes) ?: continue
                // The index says which comment it kept. If the record now holds something
                // else at that subkey, the author has rewritten it, and the owner never
                // endorsed whatever replaced it.
                val claimed = byId[comment.id]
                if (claimed == null || claimed.pointer.recordKey != recordKey) continue
                if (claimed.authorKey.isNotBlank() && claimed.authorKey != comment.authorKey) continue
                if (claimed.digest.isNotBlank() && claimed.digest != commentDigest(comment)) continue
                add(comment)
            }
        }
    }.getOrElse { emptyList() }
}
