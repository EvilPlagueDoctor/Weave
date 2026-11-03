package app.weave

import org.json.JSONObject

class GroupContentNetwork(private val client: DaemonClient) {
    // Group message DHT slots are append-only. A bounded in-memory cache prevents
    // a normal-feed + raw-overlay refresh from re-downloading the same payload.
    private val verifiedFetchCache = object : LinkedHashMap<String, WeaveMessage>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeaveMessage>?): Boolean = size > 128
    }

    fun publish(message: WeaveMessage): WeaveObjectRef {
        val root = CommentChain.ensureOwnedStore(client, STORE_NAME)
        val pointer = CommentChain.append(
            client = client,
            root = root,
            continuationName = CONTINUATION_NAME,
            payload = message.toJson().toString().encodeToByteArray(),
        )
        return WeaveObjectRef(
            objectId = message.messageId,
            recordKey = pointer.recordKey,
            subkey = pointer.subkey,
            type = WeaveObjectType.Message,
        )
    }

    fun fetch(ref: WeaveObjectRef): WeaveMessage? {
        if (ref.type != WeaveObjectType.Message || ref.recordKey.isBlank()) return null
        val key = "${ref.recordKey}:${ref.subkey}:${ref.objectId}"
        synchronized(verifiedFetchCache) { verifiedFetchCache[key]?.let { return it } }
        val result = runCatching {
            val values = client.readPublicStore(ref.recordKey, listOf(ref.subkey), true)
                .optJSONArray("values")
            val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return@runCatching null
            val message = WeaveMessage.fromJson(JSONObject(bytes.decodeToString()))
            message.takeIf { it.messageId == ref.objectId }
        }.getOrNull()
        result?.let { synchronized(verifiedFetchCache) { verifiedFetchCache[key] = it } }
        return result
    }

    companion object {
        private const val STORE_NAME = "weave_group_messages"
        private const val CONTINUATION_NAME = "weave_group_messages_next"
    }
}
