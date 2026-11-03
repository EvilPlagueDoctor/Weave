package app.weave

import org.json.JSONArray
import org.json.JSONObject

/**
 * Opportunistic, backward-compatible public-group event inventory carried on gossip.
 * Only signed pointers travel here: payloads still live in the author's public DHT.
 * A receiving node MUST verify the full event signature and content hash before use.
 */
data class GroupEventInventoryGossipV2(
    val createdAt: Long,
    val events: List<SignedGroupEventV2>,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("kind", KIND)
        .put("version", VERSION)
        .put("created_at", createdAt)
        .put("events", JSONArray().apply { events.take(MAX_EVENTS).forEach { put(it.toJson()) } })
        .toString().encodeToByteArray()

    companion object {
        const val KIND = "groups_v2_event_inventory"
        const val VERSION = 1
        const val MAX_EVENTS = 3
        const val MAX_BYTES = 8 * 1024

        fun fromBytes(bytes: ByteArray): GroupEventInventoryGossipV2? = runCatching {
            if (bytes.size > MAX_BYTES) return null
            val json = JSONObject(bytes.decodeToString())
            if (json.optString("kind") != KIND || json.optInt("version") != VERSION) return null
            val a = json.optJSONArray("events") ?: return null
            if (a.length() !in 1..MAX_EVENTS) return null
            val parsed = buildList {
                repeat(a.length()) { i ->
                    val item = a.optJSONObject(i) ?: return null
                    val event = SignedGroupEventV2.fromJson(item)
                    if (event.structuralError() != null) return null
                    add(event)
                }
            }
            GroupEventInventoryGossipV2(json.optLong("created_at"), parsed)
        }.getOrNull()
    }
}
