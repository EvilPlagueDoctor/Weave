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

    fun readPulse(rootRecordKey: String, page: Int = 0, force: Boolean = false): GroupPulse? {
        if (page != 0) return null // v2 starts with one compact current Pulse per branch.
        val state = store ?: return readPulseWithoutStore(rootRecordKey)
        return GroupBranchNetwork(client, state).readPulse(rootRecordKey)
    }

    private fun readHeaderWithoutStore(root: String): GroupBranchHeader? = runCatching {
        val result = client.readPublicStore(root, listOf(GroupBranchNetwork.HEADER_SUBKEY), true)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupBranchHeader.fromJson(org.json.JSONObject(bytes.decodeToString()))
    }.getOrNull()

    private fun readPulseWithoutStore(root: String): GroupPulse? = runCatching {
        val result = client.readPublicStore(root, listOf(GroupBranchNetwork.PULSE_SUBKEY), false)
        val bytes = CommentChain.decodeValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return null
        GroupPulse.fromJson(org.json.JSONObject(bytes.decodeToString()))
    }.getOrNull()
}
