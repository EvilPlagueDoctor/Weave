package app.weave

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Strict, length-delimited wire/DHT records for public widget networking.
 *
 * Nothing here uses terminator-based strings or permissive numeric coercion. A malformed field
 * rejects the complete record before any caller interprets it. Text is UTF-8 data only.
 */
enum class WidgetNetworkEventKind { Inputs, Text, Invite, Accept, AcceptAck, ReadAck, RandomCommit, RandomReveal, RandomRoll, Tombstone }
enum class WidgetSessionState { Active, Retired, Tombstone }

data class WidgetNetworkEvent(
    val kind: WidgetNetworkEventKind,
    val widgetSourceHashHex: String,
    val widgetInstanceIdHex: String,
    val sessionIdHex: String,
    val sequence: Long,
    val previousEventHashHex: String,
    val createdAtMs: Long,
    val inputs: List<WidgetInputValue> = emptyList(),
    val text: String = "",
    val inviteIdHex: String = "",
    val peerSessionDht: String = "",
    val acceptedEventHashHex: String = "",
    /** Non-empty only for grouped turn actions. */
    val actionIdHex: String = "",
    val actionStep: Int = 0,
    val actionCount: Int = 0,
    /** Commit/reveal and deterministic dice metadata. */
    val randomCommitHashHex: String = "",
    val randomRevealHex: String = "",
    val rollIndex: Int = 0,
    val rollSides: Int = 0,
) {
    fun canonicalBytes(): ByteArray = WidgetNetworkEventCodec.encode(this)
    fun eventHashHex(): String = sha256Hex(canonicalBytes())
}

data class WidgetSessionHeader(
    val widgetSourceHashHex: String,
    val widgetInstanceIdHex: String,
    val sessionIdHex: String,
    val state: WidgetSessionState,
    val createdAtMs: Long,
    val stateChangedAtMs: Long,
    val latestSequence: Long,
    val latestEventHashHex: String,
)

data class WidgetPublicSignal(
    val widgetSourceHashHex: String,
    val widgetInstanceIdHex: String,
    val sessionDht: String,
    val sequence: Long,
    val eventHashHex: String,
    val kind: WidgetNetworkEventKind,
)

data class WidgetDataSummary(
    val widgetSourceHashHex: String,
    val widgetInstanceIdHex: String,
    val updatedAtMs: Long,
    val acceptedEvents: Long,
    val tallies: Map<String, Long>,
    val recentEventHashes: List<String>,
)

private object WidgetWireLimits {
    const val HASH_HEX = 64
    const val SESSION_ID_HEX = 32
    const val INVITE_ID_HEX = 32
    const val ACTION_ID_HEX = 32
    const val RANDOM_SECRET_HEX = 64
    const val MAX_DHT_KEY_BYTES = 512
    const val MAX_TALLY_KEY_BYTES = 96
    const val MAX_TALLIES = 256
    const val MAX_RECENT_HASHES = 64
    const val MAX_PACKET_BYTES = 16 * 1024
}

private val HEX_64 = Regex("^[0-9a-f]{64}$")
private val HEX_32 = Regex("^[0-9a-f]{32}$")
private val NETWORK_INPUT_ID = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")

private fun requireHash(value: String, label: String): String {
    val normalized = value.lowercase()
    require(HEX_64.matches(normalized)) { "$label must be 32-byte lowercase hex" }
    return normalized
}
private fun requireSessionId(value: String, label: String): String {
    val normalized = value.lowercase()
    require(HEX_32.matches(normalized)) { "$label must be 16-byte lowercase hex" }
    return normalized
}
private fun requireDhtKey(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= WidgetWireLimits.MAX_DHT_KEY_BYTES) { "invalid widget DHT key length" }
    require(value.all { it.code in 0x21..0x7E }) { "widget DHT key contains non-canonical characters" }
    return value
}

fun validateWidgetPlainText(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES) { "widget text exceeds ${VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES} bytes" }
    value.forEach { c ->
        val code = c.code
        require(code == 0x09 || code == 0x0A || code >= 0x20) { "widget text contains a disallowed control character" }
        require(code !in 0x7F..0x9F) { "widget text contains a disallowed control character" }
    }
    return value
}

private class StrictWriter(initial: Int = 256) {
    private val bytes = ByteArrayOutputStream(initial)
    private val out = DataOutputStream(bytes)
    fun magic(s: String) { require(s.length == 4); out.write(s.toByteArray(Charsets.US_ASCII)) }
    fun u8(v: Int) { require(v in 0..255); out.writeByte(v) }
    fun u16(v: Int) { require(v in 0..65535); out.writeShort(v) }
    fun i32(v: Int) = out.writeInt(v)
    fun i64(v: Long) = out.writeLong(v)
    fun fixedHex(hex: String, expectedChars: Int) {
        val normalized = hex.lowercase()
        require(normalized.length == expectedChars && normalized.all { it in '0'..'9' || it in 'a'..'f' })
        out.write(normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
    fun string(value: String, maxBytes: Int) {
        val b = value.toByteArray(Charsets.UTF_8)
        require(b.size <= maxBytes)
        u16(b.size)
        out.write(b)
    }
    fun result(maxBytes: Int = WidgetWireLimits.MAX_PACKET_BYTES): ByteArray = bytes.toByteArray().also { require(it.size <= maxBytes) }
}

private class StrictReader(bytes: ByteArray, maxPacketBytes: Int = WidgetWireLimits.MAX_PACKET_BYTES) {
    private val input: DataInputStream
    init { require(bytes.size <= maxPacketBytes); input = DataInputStream(ByteArrayInputStream(bytes)) }
    fun magic(expected: String) {
        val b = ByteArray(4); input.readFully(b); require(b.contentEquals(expected.toByteArray(Charsets.US_ASCII))) { "bad widget wire magic" }
    }
    fun u8(): Int = input.readUnsignedByte()
    fun u16(): Int = input.readUnsignedShort()
    fun i32(): Int = input.readInt()
    fun i64(): Long = input.readLong()
    fun fixedHex(bytesCount: Int): String {
        val b = ByteArray(bytesCount); input.readFully(b); return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun string(maxBytes: Int): String {
        val n = u16()
        require(n <= maxBytes) { "widget string exceeds field limit" }
        val b = ByteArray(n); input.readFully(b)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(b)).toString()
    }
    fun done() { require(input.available() == 0) { "trailing widget wire bytes" } }
}

object WidgetNetworkEventCodec {
    private const val VERSION = 2

    fun encode(event: WidgetNetworkEvent): ByteArray {
        val source = requireHash(event.widgetSourceHashHex, "source hash")
        val instance = requireHash(event.widgetInstanceIdHex, "instance id")
        val session = requireSessionId(event.sessionIdHex, "session id")
        require(event.sequence > 0) { "sequence must be positive" }
        val previous = requireHash(event.previousEventHashHex, "previous event hash")
        require(event.createdAtMs >= 0L) { "invalid created time" }
        require(event.inputs.size <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) { "too many widget inputs" }
        val inputIds = mutableSetOf<String>()
        event.inputs.forEach { require(NETWORK_INPUT_ID.matches(it.inputId)); require(inputIds.add(it.inputId)) { "duplicate widget input" } }
        val text = validateWidgetPlainText(event.text)
        val invite = if (event.inviteIdHex.isBlank()) "0".repeat(WidgetWireLimits.INVITE_ID_HEX) else requireSessionId(event.inviteIdHex, "invite id")
        val peer = if (event.peerSessionDht.isBlank()) "" else requireDhtKey(event.peerSessionDht)
        val accepted = if (event.acceptedEventHashHex.isBlank()) "0".repeat(WidgetWireLimits.HASH_HEX) else requireHash(event.acceptedEventHashHex, "accepted event hash")
        val action = if (event.actionIdHex.isBlank()) "0".repeat(WidgetWireLimits.ACTION_ID_HEX) else requireSessionId(event.actionIdHex, "action id")
        val commit = if (event.randomCommitHashHex.isBlank()) "0".repeat(64) else requireHash(event.randomCommitHashHex, "random commitment")
        val reveal = if (event.randomRevealHex.isBlank()) "0".repeat(64) else requireHash(event.randomRevealHex, "random reveal")

        if (event.actionIdHex.isBlank()) require(event.actionStep == 0 && event.actionCount == 0) { "action metadata requires action id" }
        else {
            require(event.kind == WidgetNetworkEventKind.Inputs) { "only input events can be grouped actions" }
            require(event.actionCount in 1..VeilWidgetLimits.MAX_NETWORK_ACTION_STEPS) { "invalid action count" }
            require(event.actionStep in 1..event.actionCount) { "invalid action step" }
        }
        when (event.kind) {
            WidgetNetworkEventKind.Inputs -> require(event.inputs.isNotEmpty())
            WidgetNetworkEventKind.Text -> require(event.inputs.isEmpty() && text.isNotEmpty())
            WidgetNetworkEventKind.Invite -> require(peer.isEmpty() && invite.any { it != '0' })
            WidgetNetworkEventKind.Accept -> require(peer.isNotEmpty() && invite.any { it != '0' })
            WidgetNetworkEventKind.AcceptAck -> require(peer.isNotEmpty() && invite.any { it != '0' })
            WidgetNetworkEventKind.ReadAck -> require(accepted.any { it != '0' })
            WidgetNetworkEventKind.RandomCommit -> require(commit.any { it != '0' } && reveal.all { it == '0' })
            WidgetNetworkEventKind.RandomReveal -> require(reveal.any { it != '0' })
            WidgetNetworkEventKind.RandomRoll -> {
                require(event.rollIndex > 0) { "invalid roll index" }
                require(event.rollSides in 2..VeilWidgetLimits.MAX_DICE_SIDES) { "invalid dice sides" }
            }
            WidgetNetworkEventKind.Tombstone -> Unit
        }

        return StrictWriter().apply {
            magic("WNE2"); u8(VERSION); u8(event.kind.ordinal)
            fixedHex(source, 64); fixedHex(instance, 64); fixedHex(session, 32)
            i64(event.sequence); fixedHex(previous, 64); i64(event.createdAtMs)
            u8(event.inputs.size)
            event.inputs.forEach { input -> string(input.inputId, VeilWidgetLimits.MAX_NETWORK_INPUT_ID_BYTES); i32(input.numberValue) }
            string(text, VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES)
            fixedHex(invite, 32); string(peer, WidgetWireLimits.MAX_DHT_KEY_BYTES); fixedHex(accepted, 64)
            fixedHex(action, 32); u8(event.actionStep); u8(event.actionCount)
            fixedHex(commit, 64); fixedHex(reveal, 64); i32(event.rollIndex); i32(event.rollSides)
        }.result()
    }

    fun decode(bytes: ByteArray): WidgetNetworkEvent? = runCatching {
        val r = StrictReader(bytes)
        r.magic("WNE2"); require(r.u8() == VERSION)
        val kindOrdinal = r.u8(); require(kindOrdinal in WidgetNetworkEventKind.entries.indices)
        val source = r.fixedHex(32); val instance = r.fixedHex(32); val session = r.fixedHex(16)
        val sequence = r.i64(); require(sequence > 0)
        val previous = r.fixedHex(32); val created = r.i64(); require(created >= 0L)
        val count = r.u8(); require(count <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT)
        val inputs = ArrayList<WidgetInputValue>(count)
        repeat(count) { val id = r.string(VeilWidgetLimits.MAX_NETWORK_INPUT_ID_BYTES); require(NETWORK_INPUT_ID.matches(id)); inputs += WidgetInputValue(id, r.i32()) }
        val text = validateWidgetPlainText(r.string(VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES))
        val inviteRaw = r.fixedHex(16); val peer = r.string(WidgetWireLimits.MAX_DHT_KEY_BYTES).also { if (it.isNotBlank()) requireDhtKey(it) }
        val acceptedRaw = r.fixedHex(32); val actionRaw = r.fixedHex(16); val actionStep = r.u8(); val actionCount = r.u8()
        val commitRaw = r.fixedHex(32); val revealRaw = r.fixedHex(32); val rollIndex = r.i32(); val rollSides = r.i32(); r.done()
        fun nz(v:String)=v.takeIf { it.any { c -> c != '0' } } ?: ""
        val event = WidgetNetworkEvent(
            kind = WidgetNetworkEventKind.entries[kindOrdinal], widgetSourceHashHex = source, widgetInstanceIdHex = instance,
            sessionIdHex = session, sequence = sequence, previousEventHashHex = previous, createdAtMs = created,
            inputs = inputs, text = text, inviteIdHex = nz(inviteRaw), peerSessionDht = peer, acceptedEventHashHex = nz(acceptedRaw),
            actionIdHex = nz(actionRaw), actionStep = actionStep, actionCount = actionCount,
            randomCommitHashHex = nz(commitRaw), randomRevealHex = nz(revealRaw), rollIndex = rollIndex, rollSides = rollSides,
        )
        require(encode(event).contentEquals(bytes)) { "non-canonical widget event" }
        event
    }.getOrNull()
}

object WidgetSessionHeaderCodec {
    private const val VERSION = 1
    fun encode(header: WidgetSessionHeader): ByteArray = StrictWriter().apply {
        magic("WSH1"); u8(VERSION)
        fixedHex(requireHash(header.widgetSourceHashHex, "source hash"), 64)
        fixedHex(requireHash(header.widgetInstanceIdHex, "instance id"), 64)
        fixedHex(requireSessionId(header.sessionIdHex, "session id"), 32)
        u8(header.state.ordinal); i64(header.createdAtMs); i64(header.stateChangedAtMs); i64(header.latestSequence)
        fixedHex(requireHash(header.latestEventHashHex, "latest event hash"), 64)
    }.result(1024)

    fun decode(bytes: ByteArray): WidgetSessionHeader? = runCatching {
        val r=StrictReader(bytes,1024);r.magic("WSH1");require(r.u8()==VERSION)
        val source=r.fixedHex(32);val instance=r.fixedHex(32);val session=r.fixedHex(16)
        val state=r.u8();require(state in WidgetSessionState.entries.indices)
        val created=r.i64();val changed=r.i64();val seq=r.i64();val hash=r.fixedHex(32);r.done()
        require(created>=0&&changed>=0&&seq>=0)
        WidgetSessionHeader(source,instance,session,WidgetSessionState.entries[state],created,changed,seq,hash)
    }.getOrNull()
}

object WidgetPublicSignalCodec {
    private const val VERSION = 1
    fun encode(signal: WidgetPublicSignal): ByteArray = StrictWriter().apply {
        magic("WSG1");u8(VERSION);u8(signal.kind.ordinal)
        fixedHex(requireHash(signal.widgetSourceHashHex,"source hash"),64)
        fixedHex(requireHash(signal.widgetInstanceIdHex,"instance id"),64)
        string(requireDhtKey(signal.sessionDht),WidgetWireLimits.MAX_DHT_KEY_BYTES)
        require(signal.sequence>0);i64(signal.sequence)
        fixedHex(requireHash(signal.eventHashHex,"event hash"),64)
    }.result(700)

    fun decode(bytes: ByteArray): WidgetPublicSignal? = runCatching {
        val r=StrictReader(bytes,700);r.magic("WSG1");require(r.u8()==VERSION)
        val kind=r.u8();require(kind in WidgetNetworkEventKind.entries.indices)
        val source=r.fixedHex(32);val instance=r.fixedHex(32);val dht=requireDhtKey(r.string(WidgetWireLimits.MAX_DHT_KEY_BYTES))
        val seq=r.i64();require(seq>0);val hash=r.fixedHex(32);r.done()
        val signal=WidgetPublicSignal(source,instance,dht,seq,hash,WidgetNetworkEventKind.entries[kind])
        require(encode(signal).contentEquals(bytes)) { "non-canonical widget signal" }
        signal
    }.getOrNull()
}

object WidgetDataSummaryCodec {
    private const val VERSION=1
    fun encode(summary: WidgetDataSummary):ByteArray=StrictWriter(2048).apply{
        magic("WDS1");u8(VERSION)
        fixedHex(requireHash(summary.widgetSourceHashHex,"source hash"),64)
        fixedHex(requireHash(summary.widgetInstanceIdHex,"instance id"),64)
        i64(summary.updatedAtMs);i64(summary.acceptedEvents)
        require(summary.tallies.size<=WidgetWireLimits.MAX_TALLIES);u16(summary.tallies.size)
        summary.tallies.toSortedMap().forEach{(key,value)->
            val kb=key.toByteArray(Charsets.UTF_8);require(kb.isNotEmpty()&&kb.size<=WidgetWireLimits.MAX_TALLY_KEY_BYTES);string(key,WidgetWireLimits.MAX_TALLY_KEY_BYTES);require(value>=0);i64(value)
        }
        require(summary.recentEventHashes.size<=WidgetWireLimits.MAX_RECENT_HASHES);u8(summary.recentEventHashes.size)
        summary.recentEventHashes.forEach{fixedHex(requireHash(it,"recent event hash"),64)}
    }.result(16*1024)

    fun decode(bytes:ByteArray):WidgetDataSummary?=runCatching{
        val r=StrictReader(bytes,16*1024);r.magic("WDS1");require(r.u8()==VERSION)
        val source=r.fixedHex(32);val instance=r.fixedHex(32);val updated=r.i64();val accepted=r.i64();require(updated>=0&&accepted>=0)
        val count=r.u16();require(count<=WidgetWireLimits.MAX_TALLIES);val tallies=linkedMapOf<String,Long>()
        repeat(count){val k=r.string(WidgetWireLimits.MAX_TALLY_KEY_BYTES);require(k.isNotBlank());val v=r.i64();require(v>=0);require(tallies.put(k,v)==null)}
        val recentCount=r.u8();require(recentCount<=WidgetWireLimits.MAX_RECENT_HASHES);val recent=ArrayList<String>(recentCount);repeat(recentCount){recent+=r.fixedHex(32)};r.done()
        WidgetDataSummary(source,instance,updated,accepted,tallies,recent)
    }.getOrNull()
}

object WidgetTombstoneCodec {
    const val MESSAGE = "No ones watching anymore, Lets Disco party!"
    fun encode(widgetInstanceIdHex:String,whenMs:Long):ByteArray=StrictWriter().apply{
        magic("WST1");u8(1);fixedHex(requireHash(widgetInstanceIdHex,"instance id"),64);i64(whenMs);string(MESSAGE,128)
    }.result(256)
}

fun widgetInstanceIdHex(ownerMainDht: String, elementId: String, sourceHash: String): String =
    sha256Hex("weave-widget-instance-v1\u0000$ownerMainDht\u0000$elementId\u0000${sourceHash.lowercase()}".toByteArray(Charsets.UTF_8))

