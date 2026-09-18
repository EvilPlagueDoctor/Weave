package app.weave

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fast-path group hints shared over the existing Weave gossip transport.
 *
 * These messages are deliberately NOT authoritative. A receiver uses the branch pointer only as
 * a reason to perform the normal GroupBranchHeader/Pulse DHT verification. This means gossip can
 * make discovery and update detection feel quick without allowing a gossip peer to invent group
 * state or moderation decisions.
 */
data class GroupGossipHint(
    val pointer: GroupBranchPointer,
    val pulseGeneration: Long = 0L,
    val pulseUpdatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pointer", pointer.toJson())
        .put("pulse_generation", pulseGeneration)
        .put("pulse_updated_at", pulseUpdatedAt)

    companion object {
        fun fromJson(o: JSONObject): GroupGossipHint? = runCatching {
            val pointer = GroupBranchPointer.fromJson(o.getJSONObject("pointer")) ?: return null
            GroupGossipHint(
                pointer = pointer,
                pulseGeneration = o.optLong("pulse_generation"),
                pulseUpdatedAt = o.optLong("pulse_updated_at"),
            )
        }.getOrNull()
    }
}

data class GroupGossipPacket(
    val createdAt: Long,
    val hints: List<GroupGossipHint>,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("kind", KIND)
        .put("protocol_version", PROTOCOL_VERSION)
        .put("created_at", createdAt)
        .put("hints", JSONArray().apply { hints.take(MAX_HINTS).forEach { put(it.toJson()) } })
        .toString()
        .encodeToByteArray()

    companion object {
        const val KIND = "group_branch_hints"
        const val PROTOCOL_VERSION = 1
        const val MAX_HINTS = 16

        fun fromBytes(bytes: ByteArray): GroupGossipPacket? = runCatching {
            val o = JSONObject(bytes.decodeToString())
            if (o.optString("kind") != KIND || o.optInt("protocol_version") != PROTOCOL_VERSION) return null
            val a = o.optJSONArray("hints") ?: JSONArray()
            val hints = buildList {
                for (i in 0 until minOf(a.length(), MAX_HINTS)) {
                    a.optJSONObject(i)?.let(GroupGossipHint::fromJson)?.let(::add)
                }
            }
            GroupGossipPacket(o.optLong("created_at"), hints)
        }.getOrNull()
    }
}
