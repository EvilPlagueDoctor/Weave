package app.weave

/**
 * Compatibility facade used by the v1 screens/controller calls.
 *
 * In v2 the creator's GroupBranch DHT IS the canonical group root. Claimers publish independent
 * branch roots pointing back to this creator root.
 */
class GroupNetwork(
    private val client: DaemonClient,
    private val store: GroupStore? = null,
) {
    fun publish(group: GroupRecord, pulse: GroupPulse): GroupRecord {
        val state = store ?: throw IllegalStateException("GroupStore is required for v2 publishing")
        val owner = client.identity().getString("main_dht")
        return GroupBranchNetwork(client, state).publishOriginal(group, pulse, owner).first
    }

    fun readGroup(rootRecordKey: String, force: Boolean = true): GroupRecord? {
        val state = store ?: return readHeaderWithoutStore(rootRecordKey)?.group
        return GroupBranchNetwork(client, state).readHeader(rootRecordKey)?.group
    }

    fun readHeader(branchRoot: String): GroupBranchHeader? {
        val state = store ?: return readHeaderWithoutStore(branchRoot)
        return GroupBranchNetwork(client, state).readHeader(branchRoot)
    }

    fun readPulse(rootRecordKey: String, page: Int = 0, force: Boolean = true): GroupPulse? {
        if (page != 0) return null // v2 starts with one compact current Pulse per branch.
        val state = store ?: return readPulseWithoutStore(rootRecordKey, force)
        return GroupBranchNetwork(client, state).readPulse(rootRecordKey, force)
    }

    private fun readHeaderWithoutStore(root: String): GroupBranchHeader? = runCatching {
        val result = client.readPublicStore(root, listOf(GroupBranchNetwork.HEADER_SUBKEY), true)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupBranchHeader.fromJson(org.json.JSONObject(bytes.decodeToString()))
    }.getOrNull()

    private fun readPulseWithoutStore(root: String, force: Boolean = true): GroupPulse? = runCatching {
        val header = readHeaderWithoutStore(root)
        val pulseRoot = header?.pulseRoot.orEmpty()
        if (pulseRoot.isBlank()) {
            val result = client.readPublicStore(root, listOf(GroupBranchNetwork.PULSE_SUBKEY), force)
            val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
            return@runCatching GroupPulse.fromJson(org.json.JSONObject(bytes.decodeToString()))
        }

        val firstResult = client.readPublicStore(pulseRoot, listOf(0), force)
        val firstBytes = CommentChain.decodeValue(firstResult.optJSONArray("values")?.optJSONObject(0)) ?: return null
        val first = GroupPulse.fromJson(org.json.JSONObject(firstBytes.decodeToString()))
        val pageCount = first.pageCount.coerceIn(1, GroupBranchNetwork.PULSE_STORE_SUBKEYS)
        if (pageCount == 1) return@runCatching first.copy(page = 0, pageCount = 1)

        val result = client.readPublicStore(pulseRoot, (1 until pageCount).toList(), force)
        val values = result.optJSONArray("values")
        val pages = mutableListOf(first)
        if (values != null) for (i in 0 until values.length()) {
            val bytes = CommentChain.decodeValue(values.optJSONObject(i)) ?: continue
            val page = runCatching { GroupPulse.fromJson(org.json.JSONObject(bytes.decodeToString())) }.getOrNull() ?: continue
            if (page.groupId == first.groupId && page.generation == first.generation) pages += page
        }
        GroupPulse(
            groupId = first.groupId,
            generation = first.generation,
            updatedAt = first.updatedAt,
            page = 0,
            pageCount = pageCount,
            conversations = pages.flatMap { it.conversations }
                .distinctBy { it.conversation.objectId }
                .sortedByDescending { it.lastActivity }
                .take(GroupPulse.MAX_PULSE_CONVERSATIONS),
            removedPosts = pages.flatMap { it.removedPosts }
                .distinctBy { it.postId }
                .sortedByDescending { it.removedAt }
                .take(GroupPulse.MAX_REMOVED_POST_NOTICES),
        )
    }.getOrNull()
}
