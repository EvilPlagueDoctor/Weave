package app.weave

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Authenticated Groups-v2 custody/recovery messages.
 *
 * These travel over the daemon's authenticated send_message/mailbox transport. The embedded
 * SignedGroupEventV2 remains the canonical author statement; custody signatures authenticate the
 * custody request/receipt/recovery envelope and must never be treated as author signatures.
 */
sealed interface GroupCustodyWireMessageV2 {
    val protocolVersion: Int
}

enum class CustodyRetentionClassV2 { Requested, LocallyCapped }

data class CustodyStoreRequestV2(
    override val protocolVersion: Int = 2,
    val requestId: String,
    val groupId: String,
    val event: SignedGroupEventV2,
    val requestedRetainUntil: Long,
    val receiptRequested: Boolean,
    val requesterMainDht: String,
    val sentAt: Long,
    val signingKeyGeneration: Long,
    val signingPublicKeyHex: String,
    val signatureHex: String,
) : GroupCustodyWireMessageV2 {
    fun unsignedCanonicalBytes(): ByteArray = GroupCustodyWireCodecV2.encodeStore(this, includeSignature = false)
    companion object { const val SIGNING_DOMAIN = "weave/group-custody/store/v2" }
}

data class CustodyReceiptV2(
    override val protocolVersion: Int = 2,
    val receiptId: String,
    val groupId: String,
    val eventId: String,
    val canonicalDigestHex: String,
    val custodianMainDht: String,
    val storedAt: Long,
    val retainUntil: Long,
    val retentionClass: CustodyRetentionClassV2,
    val signingKeyGeneration: Long,
    val signingPublicKeyHex: String,
    val signatureHex: String,
) : GroupCustodyWireMessageV2 {
    fun unsignedCanonicalBytes(): ByteArray = GroupCustodyWireCodecV2.encodeReceipt(this, includeSignature = false)
    companion object { const val SIGNING_DOMAIN = "weave/group-custody/receipt/v2" }
}

data class CustodyRecoveryRequestV2(
    override val protocolVersion: Int = 2,
    val requestId: String,
    val groupId: String,
    val requesterMainDht: String,
    val sinceCreatedAt: Long,
    val untilCreatedAt: Long,
    val cursor: String,
    val maxEvents: Int,
    val sentAt: Long,
    val signingKeyGeneration: Long,
    val signingPublicKeyHex: String,
    val signatureHex: String,
) : GroupCustodyWireMessageV2 {
    fun unsignedCanonicalBytes(): ByteArray = GroupCustodyWireCodecV2.encodeRecoveryRequest(this, includeSignature = false)
    companion object { const val SIGNING_DOMAIN = "weave/group-custody/recovery-request/v2" }
}

data class CustodyRecoveryBatchV2(
    override val protocolVersion: Int = 2,
    val requestId: String,
    val responseId: String,
    val groupId: String,
    val custodianMainDht: String,
    val events: List<SignedGroupEventV2>,
    val moreAvailable: Boolean,
    val nextCursor: String,
    val generatedAt: Long,
    val signingKeyGeneration: Long,
    val signingPublicKeyHex: String,
    val signatureHex: String,
) : GroupCustodyWireMessageV2 {
    fun unsignedCanonicalBytes(): ByteArray = GroupCustodyWireCodecV2.encodeRecoveryBatch(this, includeSignature = false)
    companion object { const val SIGNING_DOMAIN = "weave/group-custody/recovery-batch/v2" }
}

object GroupCustodyWireCodecV2 {
    private val STORE_MAGIC = byteArrayOf('W'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())
    private val RECEIPT_MAGIC = byteArrayOf('W'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte())
    private val REQUEST_MAGIC = byteArrayOf('W'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '2'.code.toByte())
    private val BATCH_MAGIC = byteArrayOf('W'.code.toByte(), 'R'.code.toByte(), 'B'.code.toByte(), '2'.code.toByte())
    private const val WIRE_VERSION = 1
    private const val MAX_STRING_BYTES = 2 * 1024
    private const val MAX_EVENT_BYTES = 2 * 1024
    const val MAX_RECOVERY_EVENTS = 12
    const val MAX_WIRE_BYTES = 8 * 1024
    const val MAX_RECOVERY_BATCH_BYTES = 7_500

    fun encode(message: GroupCustodyWireMessageV2): ByteArray = when (message) {
        is CustodyStoreRequestV2 -> encodeStore(message, true)
        is CustodyReceiptV2 -> encodeReceipt(message, true)
        is CustodyRecoveryRequestV2 -> encodeRecoveryRequest(message, true)
        is CustodyRecoveryBatchV2 -> encodeRecoveryBatch(message, true)
    }.also { require(it.size <= MAX_WIRE_BYTES) { "Groups-v2 custody message exceeds daemon limit: ${it.size} bytes" } }

    fun decode(bytes: ByteArray): GroupCustodyWireMessageV2? = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_WIRE_BYTES) return null
        when {
            hasMagic(bytes, STORE_MAGIC) -> decodeStore(bytes)
            hasMagic(bytes, RECEIPT_MAGIC) -> decodeReceipt(bytes)
            hasMagic(bytes, REQUEST_MAGIC) -> decodeRecoveryRequest(bytes)
            hasMagic(bytes, BATCH_MAGIC) -> decodeRecoveryBatch(bytes)
            else -> null
        }
    }.getOrNull()

    fun looksLikeCustody(bytes: ByteArray): Boolean =
        hasMagic(bytes, STORE_MAGIC) || hasMagic(bytes, RECEIPT_MAGIC) ||
            hasMagic(bytes, REQUEST_MAGIC) || hasMagic(bytes, BATCH_MAGIC)

    internal fun encodeStore(value: CustodyStoreRequestV2, includeSignature: Boolean): ByteArray = build(STORE_MAGIC) { w ->
        w.writeByte(value.protocolVersion)
        w.writeUuidOrString(value.requestId)
        w.writeUuidOrString(value.groupId)
        w.writeEvent(value.event)
        w.writeLong(value.requestedRetainUntil)
        w.writeBoolean(value.receiptRequested)
        w.writeCompactString(value.requesterMainDht)
        w.writeLong(value.sentAt)
        w.writeSigningFields(value.signingKeyGeneration, value.signingPublicKeyHex, if (includeSignature) value.signatureHex else "")
    }

    internal fun encodeReceipt(value: CustodyReceiptV2, includeSignature: Boolean): ByteArray = build(RECEIPT_MAGIC) { w ->
        w.writeByte(value.protocolVersion)
        w.writeUuidOrString(value.receiptId)
        w.writeUuidOrString(value.groupId)
        w.writeUuidOrString(value.eventId)
        w.writeFixedHex(value.canonicalDigestHex, 32)
        w.writeCompactString(value.custodianMainDht)
        w.writeLong(value.storedAt)
        w.writeLong(value.retainUntil)
        w.writeByte(value.retentionClass.ordinal)
        w.writeSigningFields(value.signingKeyGeneration, value.signingPublicKeyHex, if (includeSignature) value.signatureHex else "")
    }

    internal fun encodeRecoveryRequest(value: CustodyRecoveryRequestV2, includeSignature: Boolean): ByteArray = build(REQUEST_MAGIC) { w ->
        w.writeByte(value.protocolVersion)
        w.writeUuidOrString(value.requestId)
        w.writeUuidOrString(value.groupId)
        w.writeCompactString(value.requesterMainDht)
        w.writeLong(value.sinceCreatedAt)
        w.writeLong(value.untilCreatedAt)
        w.writeCompactString(value.cursor)
        w.writeByte(value.maxEvents.coerceIn(1, MAX_RECOVERY_EVENTS))
        w.writeLong(value.sentAt)
        w.writeSigningFields(value.signingKeyGeneration, value.signingPublicKeyHex, if (includeSignature) value.signatureHex else "")
    }

    internal fun encodeRecoveryBatch(value: CustodyRecoveryBatchV2, includeSignature: Boolean): ByteArray = build(BATCH_MAGIC) { w ->
        require(value.events.size <= MAX_RECOVERY_EVENTS) { "Too many custody recovery events" }
        w.writeByte(value.protocolVersion)
        w.writeUuidOrString(value.requestId)
        w.writeUuidOrString(value.responseId)
        w.writeUuidOrString(value.groupId)
        w.writeCompactString(value.custodianMainDht)
        w.writeByte(value.events.size)
        value.events.forEach { w.writeEvent(it) }
        w.writeBoolean(value.moreAvailable)
        w.writeCompactString(value.nextCursor)
        w.writeLong(value.generatedAt)
        w.writeSigningFields(value.signingKeyGeneration, value.signingPublicKeyHex, if (includeSignature) value.signatureHex else "")
    }

    private fun decodeStore(bytes: ByteArray): CustodyStoreRequestV2 = read(bytes, STORE_MAGIC) { r ->
        CustodyStoreRequestV2(
            protocolVersion = r.readUnsignedByte(),
            requestId = r.readUuidOrString(),
            groupId = r.readUuidOrString(),
            event = r.readEvent(),
            requestedRetainUntil = r.readLong(),
            receiptRequested = r.readBoolean(),
            requesterMainDht = r.readCompactString(),
            sentAt = r.readLong(),
            signingKeyGeneration = r.readLong(),
            signingPublicKeyHex = r.readFixedHex(32),
            signatureHex = r.readOptionalSignature(),
        )
    }

    private fun decodeReceipt(bytes: ByteArray): CustodyReceiptV2 = read(bytes, RECEIPT_MAGIC) { r ->
        CustodyReceiptV2(
            protocolVersion = r.readUnsignedByte(),
            receiptId = r.readUuidOrString(),
            groupId = r.readUuidOrString(),
            eventId = r.readUuidOrString(),
            canonicalDigestHex = r.readFixedHex(32),
            custodianMainDht = r.readCompactString(),
            storedAt = r.readLong(),
            retainUntil = r.readLong(),
            retentionClass = CustodyRetentionClassV2.entries.getOrNull(r.readUnsignedByte()) ?: error("Unknown retention class"),
            signingKeyGeneration = r.readLong(),
            signingPublicKeyHex = r.readFixedHex(32),
            signatureHex = r.readOptionalSignature(),
        )
    }

    private fun decodeRecoveryRequest(bytes: ByteArray): CustodyRecoveryRequestV2 = read(bytes, REQUEST_MAGIC) { r ->
        CustodyRecoveryRequestV2(
            protocolVersion = r.readUnsignedByte(),
            requestId = r.readUuidOrString(),
            groupId = r.readUuidOrString(),
            requesterMainDht = r.readCompactString(),
            sinceCreatedAt = r.readLong(),
            untilCreatedAt = r.readLong(),
            cursor = r.readCompactString(),
            maxEvents = r.readUnsignedByte().coerceIn(1, MAX_RECOVERY_EVENTS),
            sentAt = r.readLong(),
            signingKeyGeneration = r.readLong(),
            signingPublicKeyHex = r.readFixedHex(32),
            signatureHex = r.readOptionalSignature(),
        )
    }

    private fun decodeRecoveryBatch(bytes: ByteArray): CustodyRecoveryBatchV2 = read(bytes, BATCH_MAGIC) { r ->
        val protocolVersion = r.readUnsignedByte()
        val requestId = r.readUuidOrString()
        val responseId = r.readUuidOrString()
        val groupId = r.readUuidOrString()
        val custodianMainDht = r.readCompactString()
        val count = r.readUnsignedByte()
        require(count <= MAX_RECOVERY_EVENTS) { "Too many custody recovery events" }
        val events = List(count) { r.readEvent() }
        CustodyRecoveryBatchV2(
            protocolVersion = protocolVersion,
            requestId = requestId,
            responseId = responseId,
            groupId = groupId,
            custodianMainDht = custodianMainDht,
            events = events,
            moreAvailable = r.readBoolean(),
            nextCursor = r.readCompactString(),
            generatedAt = r.readLong(),
            signingKeyGeneration = r.readLong(),
            signingPublicKeyHex = r.readFixedHex(32),
            signatureHex = r.readOptionalSignature(),
        )
    }

    private inline fun build(magic: ByteArray, block: (DataOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream(1024)
        DataOutputStream(out).use { w ->
            w.write(magic)
            w.writeByte(WIRE_VERSION)
            block(w)
        }
        return out.toByteArray()
    }

    private inline fun <T> read(bytes: ByteArray, magic: ByteArray, block: (DataInputStream) -> T): T {
        require(hasMagic(bytes, magic)) { "Wrong Groups-v2 custody message type" }
        DataInputStream(ByteArrayInputStream(bytes)).use { r ->
            val actual = ByteArray(4).also(r::readFully)
            require(actual.contentEquals(magic))
            require(r.readUnsignedByte() == WIRE_VERSION) { "Unsupported Groups-v2 custody wire version" }
            val value = block(r)
            require(r.available() == 0) { "Trailing Groups-v2 custody bytes" }
            return value
        }
    }

    private fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= 5 && magic.indices.all { bytes[it] == magic[it] } &&
            (bytes[4].toInt() and 0xff) == WIRE_VERSION

    private fun DataOutputStream.writeEvent(event: SignedGroupEventV2) {
        require(event.structuralError() == null) { "Structurally invalid Groups-v2 event" }
        val bytes = GroupEventWitnessCodecV2.encode(event)
        require(bytes.size <= MAX_EVENT_BYTES) { "Canonical Groups-v2 event is too large for custody" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readEvent(): SignedGroupEventV2 {
        val length = readUnsignedShort()
        require(length in 1..MAX_EVENT_BYTES) { "Invalid custody event length" }
        val bytes = ByteArray(length).also(::readFully)
        return GroupEventWitnessCodecV2.decode(bytes) ?: error("Invalid canonical Groups-v2 event")
    }

    private fun DataOutputStream.writeSigningFields(generation: Long, publicKeyHex: String, signatureHex: String) {
        writeLong(generation)
        writeFixedHex(publicKeyHex, 32)
        if (signatureHex.isBlank()) {
            writeByte(0)
        } else {
            val signature = signatureHex.hexToBytes()
            require(signature.size == 64) { "Custody signature must be Ed25519/64 bytes" }
            writeByte(signature.size)
            write(signature)
        }
    }

    private fun DataOutputStream.writeFixedHex(value: String, expectedBytes: Int) {
        val bytes = value.hexToBytes()
        require(bytes.size == expectedBytes) { "Unexpected custody crypto-field length" }
        write(bytes)
    }

    private fun DataInputStream.readFixedHex(expectedBytes: Int): String =
        ByteArray(expectedBytes).also(::readFully).toHex()

    private fun DataInputStream.readOptionalSignature(): String {
        val length = readUnsignedByte()
        require(length == 0 || length == 64) { "Invalid custody signature length" }
        return if (length == 0) "" else ByteArray(length).also(::readFully).toHex()
    }

    private fun DataOutputStream.writeCompactString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES) { "Custody string too large" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readCompactString(): String {
        val length = readUnsignedShort()
        require(length <= MAX_STRING_BYTES) { "Custody string too large" }
        return ByteArray(length).also(::readFully).decodeToString()
    }

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
        else -> error("Invalid custody ID encoding")
    }
}
