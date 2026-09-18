package app.weave

import org.json.JSONObject

class GroupContentNetwork(private val client: DaemonClient) {
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

    fun fetch(ref: WeaveObjectRef): WeaveMessage? = runCatching {
        if (ref.type != WeaveObjectType.Message || ref.recordKey.isBlank()) return null
        val values = client.readPublicStore(ref.recordKey, listOf(ref.subkey), true)
            .optJSONArray("values")
        val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return null
        val message = WeaveMessage.fromJson(JSONObject(bytes.decodeToString()))
        if (message.messageId != ref.objectId) null else message
    }.getOrNull()

    companion object {
        private const val STORE_NAME = "weave_group_messages"
        private const val CONTINUATION_NAME = "weave_group_messages_next"
    }
}
