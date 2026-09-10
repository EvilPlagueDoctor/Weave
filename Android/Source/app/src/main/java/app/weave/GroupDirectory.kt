package app.weave

import org.json.JSONArray
import org.json.JSONObject

/** Subkey 2 of a person's profile app-root advertises groups they created or independently claim. */
const val GROUP_DIRECTORY_SUBKEY = 2

/**
 * User-first distributed group discovery. No global group index is required:
 * discover profile -> read this tiny directory -> optionally verify the selected group/branch DHT.
 *
 * Private/member-only groups are never advertised here.
 */
class GroupDirectoryNetwork(private val client: DaemonClient) {
    fun publish(profileStoreId: String, advertiserMainDht: String, entries: List<GroupDirectoryEntry>) {
        val publicEntries = entries
            .filter { it.visibility != GroupVisibility.MembersOnly }
            .filter { it.advertiserMainDht == advertiserMainDht }
            .distinctBy { it.groupId to it.branchId }
            .take(MAX_DIRECTORY_ENTRIES)
        val payload = JSONObject()
            .put("v", 2)
            .put("advertiser", advertiserMainDht)
            .put("entries", JSONArray().apply { publicEntries.forEach { put(it.toJson()) } })
            .toString()
            .encodeToByteArray()
        client.writeStore(profileStoreId, GROUP_DIRECTORY_SUBKEY, payload)
    }

    fun read(profileRootRecordKey: String, expectedAdvertiserMainDht: String): List<GroupDirectoryEntry> =
        runCatching {
            val values = client.readPublicStore(
                profileRootRecordKey,
                listOf(GROUP_DIRECTORY_SUBKEY),
                true,
            ).optJSONArray("values")
            val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return emptyList()
            val root = JSONObject(bytes.decodeToString())
            if (root.optInt("v") != 2) return emptyList()
            if (root.optString("advertiser") != expectedAdvertiserMainDht) return emptyList()
            val entries = root.optJSONArray("entries") ?: return emptyList()
            buildList {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i)?.let(GroupDirectoryEntry::fromJson) ?: continue
                    if (entry.advertiserMainDht != expectedAdvertiserMainDht) continue
                    if (entry.visibility == GroupVisibility.MembersOnly) continue
                    add(entry)
                }
            }
        }.getOrElse { emptyList() }

    companion object {
        const val MAX_DIRECTORY_ENTRIES = 128
    }
}
