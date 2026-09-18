package app.weave

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/** Canonical Groups-v2 submission types. Transport-specific actions never belong here. */
enum class GroupEventTypeV2 { PostSubmitted, CommentSubmitted }

enum class GroupEventTargetModeV2 { Group, SpecificBranches }

data class GroupEventTargetV2(
    val mode: GroupEventTargetModeV2 = GroupEventTargetModeV2.Group,
    val branchIds: List<String> = emptyList(),
) {
    fun normalized(): GroupEventTargetV2 = copy(
        branchIds = if (mode == GroupEventTargetModeV2.SpecificBranches) {
            branchIds.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        } else emptyList()
    )

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("branch_ids", JSONArray(normalized().branchIds))

    companion object {
        fun fromJson(o: JSONObject): GroupEventTargetV2 {
            val mode = runCatching { GroupEventTargetModeV2.valueOf(o.optString("mode")) }
                .getOrDefault(GroupEventTargetModeV2.Group)
            val a = o.optJSONArray("branch_ids") ?: JSONArray()
            return GroupEventTargetV2(
                mode = mode,
                branchIds = List(a.length()) { a.optString(it) },
            ).normalized()
        }
    }
}

data class GroupPayloadReferenceV2(
    val objectType: WeaveObjectType,
    val recordKey: String,
    val subkey: Int,
    val objectId: String,
    val parentObjectId: String = "",
    val schemaVersion: Int = 1,
) {
    fun toWeaveRef(): WeaveObjectRef = WeaveObjectRef(
        objectId = objectId,
        recordKey = recordKey,
        subkey = subkey,
        type = objectType,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("object_type", objectType.name)
        .put("record_key", recordKey)
        .put("subkey", subkey)
        .put("object_id", objectId)
        .put("parent_object_id", parentObjectId)
        .put("schema_version", schemaVersion)

    companion object {
        fun fromJson(o: JSONObject): GroupPayloadReferenceV2 = GroupPayloadReferenceV2(
            objectType = WeaveObjectType.fromWire(o.optString("object_type")),
            recordKey = o.getString("record_key"),
            subkey = o.optInt("subkey"),
            objectId = o.getString("object_id"),
            parentObjectId = o.optString("parent_object_id"),
            schemaVersion = o.optInt("schema_version", 1),
        )
    }
}

/**
 * Immutable, signed Groups-v2 submission envelope.
 *
 * The signing key is an app/account Ed25519 key managed by the VeilKnit daemon. The public key is
 * embedded so the signature can be checked anywhere. Binding that key to a remote profile/main DHT
 * is deliberately a separate validation step; Phase 1 marks local events as bound and imported
 * events as signature-valid-but-unbound until profile key publication is added.
 */
data class SignedGroupEventV2(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: GroupEventTypeV2,
    val groupId: String,
    val authorMainDht: String,
    val createdAt: Long,
    val expiresAt: Long,
    val target: GroupEventTargetV2,
    val payload: GroupPayloadReferenceV2,
    val contentHashHex: String,
    val signingKeyGeneration: Long,
    val signingPublicKeyHex: String,
    val signatureHex: String,
) {
    fun unsignedCanonicalBytes(): ByteArray = GroupEventV2Codec.encodeUnsigned(this)
    fun canonicalDigestHex(): String = sha256Hex(unsignedCanonicalBytes())

    /** Cheap structural validation performed before signature/DHT work on untrusted events. */
    fun structuralError(now: Long = System.currentTimeMillis()): String? {
        if (protocolVersion != PROTOCOL_VERSION) return "unsupported-version"
        if (eventId.isBlank() || eventId.encodeToByteArray().size > MAX_ID_BYTES) return "event-id"
        if (groupId.isBlank() || groupId.encodeToByteArray().size > MAX_ID_BYTES) return "group-id"
        if (authorMainDht.isBlank() || authorMainDht.encodeToByteArray().size > MAX_DHT_KEY_BYTES) return "author"
        if (createdAt <= 0L || createdAt > now + MAX_FUTURE_SKEW_MS) return "created-at"
        if (expiresAt <= createdAt || expiresAt - createdAt > MAX_EVENT_LIFETIME_MS) return "expires-at"
        if (expiresAt <= now - MAX_PAST_EXPIRY_GRACE_MS) return "expired"
        val normalizedTarget = target.normalized()
        if (normalizedTarget.branchIds.size > MAX_TARGET_BRANCHES) return "target-branches"
        if (normalizedTarget.branchIds.any { it.isBlank() || it.encodeToByteArray().size > MAX_ID_BYTES }) return "target-branch-id"
        if (payload.recordKey.isBlank() || payload.recordKey.encodeToByteArray().size > MAX_DHT_KEY_BYTES) return "record-key"
        if (payload.objectId.isBlank() || payload.objectId.encodeToByteArray().size > MAX_ID_BYTES) return "object-id"
        if (payload.parentObjectId.encodeToByteArray().size > MAX_ID_BYTES) return "parent-object-id"
        if (payload.subkey !in 0..MAX_PAYLOAD_SUBKEY) return "subkey"
        if (payload.schemaVersion !in 1..MAX_SCHEMA_VERSION) return "schema-version"
        if (!contentHashHex.matches(Regex("[0-9a-fA-F]{64}"))) return "content-hash"
        if (!signingPublicKeyHex.matches(Regex("[0-9a-fA-F]{64}"))) return "signing-key"
        if (!signatureHex.matches(Regex("[0-9a-fA-F]{128}"))) return "signature"
        if (signingKeyGeneration < 0L) return "signing-generation"
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("protocol_version", protocolVersion)
        .put("event_id", eventId)
        .put("event_type", eventType.name)
        .put("group_id", groupId)
        .put("author_main_dht", authorMainDht)
        .put("created_at", createdAt)
        .put("expires_at", expiresAt)
        .put("target", target.normalized().toJson())
        .put("payload", payload.toJson())
        .put("content_hash_hex", contentHashHex.lowercase())
        .put("signing_key_generation", signingKeyGeneration)
        .put("signing_public_key_hex", signingPublicKeyHex.lowercase())
        .put("signature_hex", signatureHex.lowercase())

    companion object {
        const val PROTOCOL_VERSION = 2
        const val SIGNING_DOMAIN = "weave/group-event/v2"
        const val MAX_TARGET_BRANCHES = 32
        const val MAX_ID_BYTES = 192
        const val MAX_DHT_KEY_BYTES = 384
        const val MAX_PAYLOAD_SUBKEY = 4095
        const val MAX_SCHEMA_VERSION = 1024
        const val MAX_FUTURE_SKEW_MS = 10L * 60L * 1000L
        const val MAX_EVENT_LIFETIME_MS = 8L * 24L * 60L * 60L * 1000L
        const val MAX_PAST_EXPIRY_GRACE_MS = 5L * 60L * 1000L

        fun fromJson(o: JSONObject): SignedGroupEventV2 = SignedGroupEventV2(
            protocolVersion = o.getInt("protocol_version"),
            eventId = o.getString("event_id"),
            eventType = GroupEventTypeV2.valueOf(o.getString("event_type")),
            groupId = o.getString("group_id"),
            authorMainDht = o.getString("author_main_dht"),
            createdAt = o.getLong("created_at"),
            expiresAt = o.getLong("expires_at"),
            target = GroupEventTargetV2.fromJson(o.getJSONObject("target")),
            payload = GroupPayloadReferenceV2.fromJson(o.getJSONObject("payload")),
            contentHashHex = o.getString("content_hash_hex").lowercase(),
            signingKeyGeneration = o.getLong("signing_key_generation"),
            signingPublicKeyHex = o.getString("signing_public_key_hex").lowercase(),
            signatureHex = o.getString("signature_hex").lowercase(),
        )

    }
}

/** Byte-for-byte canonical encoder used for signatures and event identity comparisons. */
object GroupEventV2Codec {
    private const val MAX_CANONICAL_FIELD_BYTES = 4096

    fun encodeUnsigned(event: SignedGroupEventV2): ByteArray {
        val e = event.copy(target = event.target.normalized(), signatureHex = "")
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { w ->
            w.writeInt(e.protocolVersion)
            w.writeUtf8(e.eventId)
            w.writeUtf8(e.eventType.name)
            w.writeUtf8(e.groupId)
            w.writeUtf8(e.authorMainDht)
            w.writeLong(e.createdAt)
            w.writeLong(e.expiresAt)
            w.writeUtf8(e.target.mode.name)
            require(e.target.branchIds.size <= SignedGroupEventV2.MAX_TARGET_BRANCHES) { "Too many Groups-v2 target branches" }
            w.writeInt(e.target.branchIds.size)
            e.target.branchIds.forEach { w.writeUtf8(it) }
            w.writeUtf8(e.payload.objectType.name)
            w.writeUtf8(e.payload.recordKey)
            w.writeInt(e.payload.subkey)
            w.writeUtf8(e.payload.objectId)
            w.writeUtf8(e.payload.parentObjectId)
            w.writeInt(e.payload.schemaVersion)
            w.writeUtf8(e.contentHashHex.lowercase())
            w.writeLong(e.signingKeyGeneration)
            w.writeUtf8(e.signingPublicKeyHex.lowercase())
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_CANONICAL_FIELD_BYTES) { "Canonical field is too large" }
        writeInt(bytes.size)
        write(bytes)
    }
}

/** Deterministic hash of the exact post/comment payload that a GroupEventV2 refers to. */
fun groupV2MessageHash(message: WeaveMessage): String {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { w ->
        fun str(value: String) {
            val bytes = value.encodeToByteArray()
            w.writeInt(bytes.size)
            w.write(bytes)
        }
        fun ref(value: WeaveObjectRef?) {
            w.writeBoolean(value != null)
            if (value != null) {
                str(value.objectId); str(value.recordKey); w.writeInt(value.subkey); str(value.type.name)
            }
        }
        w.writeInt(message.version)
        str(message.messageId)
        ref(message.container)
        str(message.authorId)
        str(message.authorName)
        str(message.body)
        str(message.title.orEmpty())
        str(message.thumbnailBase64.orEmpty())
        w.writeInt(message.fullMedia.size)
        message.fullMedia.forEach(::ref)
        ref(message.replyTo)
        ref(message.root)
        w.writeLong(message.createdAt)
        w.writeBoolean(message.editedAt != null)
        message.editedAt?.let(w::writeLong)
        w.writeInt(message.flags)
        // Message.signature is transport/content metadata and intentionally included if present.
        str(message.signature)
    }
    return sha256Hex(output.toByteArray())
}

