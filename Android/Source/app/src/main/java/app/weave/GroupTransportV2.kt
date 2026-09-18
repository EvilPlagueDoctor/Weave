package app.weave

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Small Groups-v2 wire packet used by authenticated direct/mailbox delivery. The event itself is
 * signed; this wrapper only records routing intent and timing.
 */
data class GroupEventTransportPacketV2(
    val event: SignedGroupEventV2,
    val targetOwnerMainDht: String = "",
    val sentAt: Long = System.currentTimeMillis(),
) {
    fun toBytes(): ByteArray {
        require(event.structuralError() == null) { "Structurally invalid Groups-v2 event" }
        require(targetOwnerMainDht.encodeToByteArray().size <= SignedGroupEventV2.MAX_DHT_KEY_BYTES) { "Groups-v2 target is too large" }
        return org.json.JSONObject()
            .put("type", TYPE)
            .put("event", event.toJson())
            .put("target_owner_main_dht", targetOwnerMainDht)
            .put("sent_at", sentAt)
            .toString()
            .encodeToByteArray()
            .also { require(it.size <= MAX_PACKET_BYTES) { "Groups-v2 transport packet is too large" } }
    }

    companion object {
        const val TYPE = "weave.group.event-transport.v2"
        private const val MAX_PACKET_BYTES = 8 * 1024

        fun fromBytes(bytes: ByteArray): GroupEventTransportPacketV2? = runCatching {
            if (bytes.isEmpty() || bytes.size > MAX_PACKET_BYTES) return null
            val o = org.json.JSONObject(bytes.decodeToString())
            if (o.optString("type") != TYPE) return null
            val packet = GroupEventTransportPacketV2(
                event = SignedGroupEventV2.fromJson(o.getJSONObject("event")),
                targetOwnerMainDht = o.optString("target_owner_main_dht"),
                sentAt = o.optLong("sent_at"),
            )
            if (packet.targetOwnerMainDht.encodeToByteArray().size > SignedGroupEventV2.MAX_DHT_KEY_BYTES) return null
            if (packet.event.structuralError() != null) return null
            packet
        }.getOrNull()
    }
}

/**
 * Compact public-witness codec.
 *
 * ServiceRequest messages have a strict daemon-side serialized-size ceiling. JSON-wrapping an
 * already signed event wastes enough bytes to hit that ceiling once the daemon adds its own
 * metadata/Base64 layer. This codec carries the exact same signed fields in a small deterministic
 * binary envelope. It is NOT a second signature format: signature verification still uses
 * SignedGroupEventV2.unsignedCanonicalBytes().
 */
object GroupEventWitnessCodecV2 {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), '2'.code.toByte())
    private const val WIRE_VERSION = 1
    private const val MAX_STRING_BYTES = 2 * 1024
    private const val MAX_BRANCHES = SignedGroupEventV2.MAX_TARGET_BRANCHES
    private const val MAX_ENCODED_EVENT_BYTES = 2 * 1024

    fun encode(event: SignedGroupEventV2): ByteArray {
        require(event.structuralError() == null) { "Structurally invalid Groups-v2 event" }
        val out = ByteArrayOutputStream(512)
        DataOutputStream(out).use { w ->
            w.write(MAGIC)
            w.writeByte(WIRE_VERSION)
            require(event.protocolVersion in 0..255) { "Unsupported Groups-v2 protocol version" }
            w.writeByte(event.protocolVersion)
            w.writeUuidOrString(event.eventId)
            w.writeByte(event.eventType.ordinal)
            w.writeUuidOrString(event.groupId)
            w.writeCompactString(event.authorMainDht)
            w.writeLong(event.createdAt)
            w.writeLong(event.expiresAt)

            val target = event.target.normalized()
            w.writeByte(target.mode.ordinal)
            require(target.branchIds.size <= MAX_BRANCHES) { "Too many explicit Groups-v2 target branches" }
            w.writeByte(target.branchIds.size)
            target.branchIds.forEach { w.writeCompactString(it) }

            w.writeByte(event.payload.objectType.ordinal)
            w.writeCompactString(event.payload.recordKey)
            w.writeInt(event.payload.subkey)
            w.writeUuidOrString(event.payload.objectId)
            w.writeUuidOrString(event.payload.parentObjectId)
            w.writeInt(event.payload.schemaVersion)

            val contentHash = event.contentHashHex.hexToBytes().also { require(it.size == 32) { "Groups-v2 content hash must be 32 bytes" } }
            val publicKey = event.signingPublicKeyHex.hexToBytes().also { require(it.size == 32) { "Groups-v2 signing public key must be 32 bytes" } }
            val signature = event.signatureHex.hexToBytes().also { require(it.size == 64) { "Groups-v2 signature must be 64 bytes" } }
            w.writeSizedBytes(contentHash, expectedMax = 32)
            w.writeLong(event.signingKeyGeneration)
            w.writeSizedBytes(publicKey, expectedMax = 32)
            w.writeSizedBytes(signature, expectedMax = 64)
        }
        return out.toByteArray().also {
            require(it.size <= MAX_ENCODED_EVENT_BYTES) { "Groups-v2 witness event is too large" }
        }
    }

    fun decode(bytes: ByteArray): SignedGroupEventV2? = runCatching {
        if (!looksLikeWitness(bytes) || bytes.size > MAX_ENCODED_EVENT_BYTES) return null
        DataInputStream(ByteArrayInputStream(bytes)).use { r ->
            val magic = ByteArray(MAGIC.size)
            r.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return null
            if (r.readUnsignedByte() != WIRE_VERSION) return null

            val protocolVersion = r.readUnsignedByte()
            val eventId = r.readUuidOrString()
            val eventType = GroupEventTypeV2.entries.getOrNull(r.readUnsignedByte()) ?: return null
            val groupId = r.readUuidOrString()
            val authorMainDht = r.readCompactString()
            val createdAt = r.readLong()
            val expiresAt = r.readLong()

            val targetMode = GroupEventTargetModeV2.entries.getOrNull(r.readUnsignedByte()) ?: return null
            val branchCount = r.readUnsignedByte()
            val branchIds = List(branchCount) { r.readCompactString() }

            val objectType = WeaveObjectType.entries.getOrNull(r.readUnsignedByte()) ?: return null
            val recordKey = r.readCompactString()
            val subkey = r.readInt()
            val objectId = r.readUuidOrString()
            val parentObjectId = r.readUuidOrString()
            val schemaVersion = r.readInt()

            val contentHash = r.readSizedBytes(32).also { require(it.size == 32) { "Groups-v2 content hash must be 32 bytes" } }
            val signingKeyGeneration = r.readLong()
            val signingPublicKey = r.readSizedBytes(32).also { require(it.size == 32) { "Groups-v2 signing public key must be 32 bytes" } }
            val signature = r.readSizedBytes(64).also { require(it.size == 64) { "Groups-v2 signature must be 64 bytes" } }
            val contentHashHex = contentHash.toHex()
            val signingPublicKeyHex = signingPublicKey.toHex()
            val signatureHex = signature.toHex()
            if (r.available() != 0) return null

            val event = SignedGroupEventV2(
                protocolVersion = protocolVersion,
                eventId = eventId,
                eventType = eventType,
                groupId = groupId,
                authorMainDht = authorMainDht,
                createdAt = createdAt,
                expiresAt = expiresAt,
                target = GroupEventTargetV2(targetMode, branchIds).normalized(),
                payload = GroupPayloadReferenceV2(
                    objectType = objectType,
                    recordKey = recordKey,
                    subkey = subkey,
                    objectId = objectId,
                    parentObjectId = parentObjectId,
                    schemaVersion = schemaVersion,
                ),
                contentHashHex = contentHashHex,
                signingKeyGeneration = signingKeyGeneration,
                signingPublicKeyHex = signingPublicKeyHex,
                signatureHex = signatureHex,
            )
            if (event.structuralError() != null) return null
            event
        }
    }.getOrNull()

    fun looksLikeWitness(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + 2 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private fun DataOutputStream.writeCompactString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES) { "Groups-v2 witness string too large" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readCompactString(): String {
        val length = readUnsignedShort()
        require(length <= MAX_STRING_BYTES) { "Groups-v2 witness string too large" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.decodeToString()
    }

    /** UUIDs dominate several IDs in normal events, so store them as 16 bytes when possible. */
    private fun DataOutputStream.writeUuidOrString(value: String) {
        val uuid = value.takeIf(String::isNotBlank)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuid != null) {
            writeByte(1)
            writeLong(uuid.mostSignificantBits)
            writeLong(uuid.leastSignificantBits)
        } else {
            writeByte(0)
            writeCompactString(value)
        }
    }

    private fun DataInputStream.readUuidOrString(): String = when (readUnsignedByte()) {
        1 -> UUID(readLong(), readLong()).toString()
        0 -> readCompactString()
        else -> error("Invalid Groups-v2 witness ID encoding")
    }

    private fun DataOutputStream.writeSizedBytes(bytes: ByteArray, expectedMax: Int) {
        require(bytes.size <= expectedMax && bytes.size <= 255) { "Groups-v2 witness byte field too large" }
        writeByte(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSizedBytes(max: Int): ByteArray {
        val length = readUnsignedByte()
        require(length <= max) { "Groups-v2 witness byte field too large" }
        return ByteArray(length).also { readFully(it) }
    }
}

/** Compact opaque wrapper used for member-only Groups-v2 public witness copies. */
object GroupEventTransportCryptoV2 {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'G'.code.toByte(), 'X'.code.toByte(), '2'.code.toByte())
    private const val WIRE_VERSION = 1
    private val aad = MAGIC + byteArrayOf(WIRE_VERSION.toByte())
    private val random = SecureRandom()

    fun encrypt(packet: GroupEventTransportPacketV2, key: ByteArray): ByteArray {
        require(key.size == 32) { "Private group intake key must be 256 bits" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(GroupEventWitnessCodecV2.encode(packet.event))
        return ByteArrayOutputStream(MAGIC.size + 1 + nonce.size + ciphertext.size).also { out ->
            out.write(MAGIC)
            out.write(WIRE_VERSION)
            out.write(nonce)
            out.write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(bytes: ByteArray, key: ByteArray): GroupEventTransportPacketV2? = runCatching {
        if (key.size != 32 || !looksEncrypted(bytes)) return null
        val minimum = MAGIC.size + 1 + 12 + 16
        if (bytes.size < minimum) return null
        val input = ByteArrayInputStream(bytes)
        val magic = ByteArray(MAGIC.size).also { input.read(it) }
        if (!magic.contentEquals(MAGIC) || input.read() != WIRE_VERSION) return null
        val nonce = ByteArray(12).also {
            if (input.read(it) != it.size) return null
        }
        val ciphertext = input.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val event = GroupEventWitnessCodecV2.decode(cipher.doFinal(ciphertext)) ?: return null
        GroupEventTransportPacketV2(event)
    }.getOrNull()

    fun looksEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + 1 && MAGIC.indices.all { bytes[it] == MAGIC[it] } &&
            (bytes[MAGIC.size].toInt() and 0xff) == WIRE_VERSION
}
