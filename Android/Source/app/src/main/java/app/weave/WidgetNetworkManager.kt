package app.weave

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class WidgetHostEventKind { Input, Committed, Action, ActionCommitted, Text, Invite, InviteAccepted, SessionReady, RandomReady, Roll }

/**
 * Already-validated event presented to the widget runtime. [token] is an opaque local handle; it
 * is never a DHT key, identity, hash, or other protocol field visible to widget source.
 */
data class WidgetHostEvent(
    val kind: WidgetHostEventKind,
    val token: String = "",
    val inputs: List<WidgetInputValue> = emptyList(),
    val actionSteps: List<List<WidgetInputValue>> = emptyList(),
    val text: String = "",
    val myPlayer: Int = 0,
    val eventPlayer: Int = 0,
    val randomFirstPlayer: Int = 0,
    val rollIndex: Int = 0,
    val rollSides: Int = 0,
    val rollValue: Int = 0,
)

/** Host surface exposed to the VM. There are deliberately no raw DHT/mailbox/socket methods. */
interface WidgetNetworkHost {
    /** One network action: at most two declared inputs plus at most one inert UTF-8 text line. */
    fun send(values: List<WidgetInputValue> = emptyList(), text: String = "")
    /** Opens/closes a bounded multi-step turn action; sends made while open are buffered. */
    fun beginAction()
    fun endAction()
    /** Deterministic commit/reveal-backed die roll. */
    fun roll(sides: Int)
    fun openInvitation()
    fun accept(token: String)
    fun reject(token: String)
    fun acceptInvite(token: String)
    fun declineInvite(token: String)
    /** Stops this activated widget's local session/polling state. Published history is not erased. */
    fun close()
    suspend fun poll(): List<WidgetHostEvent>
}

enum class WidgetMailboxDisposition { NotWidget, Consumed, RetryLater }

/**
 * Public Widget Networking v1.
 *
 * The widget VM knows only declared inputs and inert text. This class owns every protocol detail:
 * session-DHT creation/rotation, strict records, one-hour spectator-readable ServiceRequests,
 * optional durable mailbox pointers, replay locking, and the publisher's long-lived Data DHT.
 */
class WidgetNetworkManager(
    context: Context,
    private val client: DaemonClient,
    private val ownMainDht: () -> String,
    private val logger: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val vault = PrivateVault.get(appContext)
    private val random = SecureRandom()
    private val recentSignals = ConcurrentHashMap<String, ArrayDeque<WidgetPublicSignal>>()
    private val publishRate = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val readRate = ConcurrentHashMap<String, ArrayDeque<Long>>()

    data class OwnedData(
        val instanceId: String,
        val sourceHash: String,
        val storeId: String,
        val recordKey: String,
        val inputs: List<WidgetInputDefinition>,
        val mailboxCopy: Boolean,
        val seenLocks: LinkedHashMap<String, String> = linkedMapOf(),
    )

    data class Session(
        val contextKey: String,
        val instanceId: String,
        val sourceHash: String,
        val hostMainDht: String,
        val storeId: String,
        val recordKey: String,
        val sessionId: String,
        var createdAt: Long,
        var state: WidgetSessionState,
        var stateChangedAt: Long,
        var sequence: Long,
        var previousHash: String,
        var messageCursor: Int,
    )

    companion object {
        val PUBLIC_SERVICE_ID: String = sha256Hex("weave/widget/public-network/v1".toByteArray())
        val PUBLIC_MANIFEST_HASH: String = sha256Hex("weave/widget/public-network/manifest/v1".toByteArray())
        private const val DATA_INDEX_KEY = "widget_network/data_index_v1"
        private const val SESSION_INDEX_KEY = "widget_network/session_index_v1"
        private const val MAX_LOCKS_PER_WIDGET = 2048
        private const val MAX_RECENT_SIGNALS = 64
        private const val DATA_SUMMARY_SUBKEY = 0
        private const val MAX_PUBLICATIONS_PER_MINUTE = 120
        private const val MAX_REMOTE_READS_PER_MINUTE = 300
    }

    @Synchronized
    fun ensurePublisherDataDht(elementId: String, sourceHash: String, program: WidgetProgram): String {
        // Every published widget instance may have a publisher-owned Data DHT. Reading it is
        // ordinary widget data access and does not itself make the widget "online".
        val owner = ownMainDht().ifBlank { error("Weave identity is not ready") }
        val instance = widgetInstanceIdHex(owner, elementId, sourceHash)
        val all = loadOwnedData().toMutableList()
        all.firstOrNull { it.instanceId == instance && it.sourceHash == sourceHash }?.let { return it.recordKey }

        val safe = instance.take(20)
        val created = client.createStore("weave_widget_data_$safe", VeilWidgetLimits.WIDGET_DATA_DHT_SUBKEYS).getJSONObject("store")
        val owned = OwnedData(
            instanceId = instance,
            sourceHash = sourceHash,
            storeId = created.getString("store_id"),
            recordKey = created.getString("record_key"),
            inputs = program.inputs.map { it.copy() },
            mailboxCopy = program.mailboxCopy,
        )
        val summary = WidgetDataSummary(sourceHash, instance, System.currentTimeMillis(), 0, emptyMap(), emptyList())
        client.writeStore(owned.storeId, DATA_SUMMARY_SUBKEY, WidgetDataSummaryCodec.encode(summary))
        all += owned
        saveOwnedData(all)
        logger("widget network: created Data DHT ${short(owned.recordKey)} for ${instance.take(12)}")
        return owned.recordKey
    }

    /** Reading the publisher's databank is a normal public-DHT read, not an online widget action. */
    fun readPublisherDataDht(recordKey: String): WidgetDataSummary? = runCatching {
        val values = client.readPublicStore(recordKey, listOf(DATA_SUMMARY_SUBKEY), true).optJSONArray("values")
        val bytes = CommentChain.decodeValue(values?.optJSONObject(0)) ?: return@runCatching null
        WidgetDataSummaryCodec.decode(bytes)
    }.getOrNull()

    @Synchronized
    fun submit(
        hostMainDht: String,
        elementId: String,
        sourceHash: String,
        program: WidgetProgram,
        inputs: List<WidgetInputValue> = emptyList(),
        text: String = "",
    ): Result<WidgetPublicSignal> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        require(inputs.isNotEmpty() || text.isNotEmpty()) { "widget event must contain an input and/or text" }
        require(inputs.size <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) { "too many inputs in one widget event" }
        validateOutgoingInputs(program, inputs)
        val safeText = if (text.isNotEmpty()) validateWidgetPlainText(text) else ""
        publishEvent(
            hostMainDht = hostMainDht,
            elementId = elementId,
            sourceHash = sourceHash,
            program = program,
            kind = if (inputs.isNotEmpty()) WidgetNetworkEventKind.Inputs else WidgetNetworkEventKind.Text,
            inputs = inputs,
            text = safeText,
        )
    }


    data class ActionPublication(val actionIdHex: String, val signals: List<WidgetPublicSignal>)

    /** Publishes one logical turn action as 1..5 separately hash-pinned input steps. */
    @Synchronized
    fun submitAction(
        hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram,
        steps: List<List<WidgetInputValue>>,
    ): Result<ActionPublication> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        require(steps.size in 1..VeilWidgetLimits.MAX_NETWORK_ACTION_STEPS) { "widget action needs 1..${VeilWidgetLimits.MAX_NETWORK_ACTION_STEPS} steps" }
        val ids = steps.first().map { it.inputId }.toSet()
        require(ids.isNotEmpty()) { "widget action step cannot be empty" }
        steps.forEach { step ->
            require(step.size <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) { "too many inputs in action step" }
            require(step.map { it.inputId }.toSet() == ids) { "all action steps must use the same declared inputs" }
            validateOutgoingInputs(program, step)
        }
        val actionId = randomHex(16)
        val signals = steps.mapIndexed { index, step ->
            publishEvent(
                hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.Inputs, inputs = step,
                actionIdHex = actionId, actionStep = index + 1, actionCount = steps.size,
            )
        }
        ActionPublication(actionId, signals)
    }

    @Synchronized
    fun publishRandomCommit(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, commitmentHex: String): Result<WidgetPublicSignal> = runCatching {
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.RandomCommit, randomCommitHashHex = commitmentHex)
    }

    @Synchronized
    fun publishRandomReveal(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, secretHex: String): Result<WidgetPublicSignal> = runCatching {
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.RandomReveal, randomRevealHex = secretHex)
    }

    @Synchronized
    fun publishRandomRoll(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, rollIndex: Int, sides: Int): Result<WidgetPublicSignal> = runCatching {
        require(rollIndex > 0) { "invalid roll index" }
        require(sides in 2..VeilWidgetLimits.MAX_DICE_SIDES) { "invalid dice sides" }
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.RandomRoll, rollIndex = rollIndex, rollSides = sides)
    }

    /** First step of the three-way turn-based session handshake. */
    @Synchronized
    fun openInvitation(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram): Result<Pair<String, WidgetPublicSignal>> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        val inviteId = randomHex(16)
        inviteId to publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.Invite, inviteIdHex = inviteId)
    }

    /** Second step: a participant accepts an invitation and names only its own temporary Session DHT. */
    @Synchronized
    fun acceptInvitation(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, inviteIdHex: String, inviterSessionDht: String): Result<WidgetPublicSignal> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.Accept, inviteIdHex = inviteIdHex, peerSessionDht = inviterSessionDht)
    }

    /** Final inviter acknowledgement. Once observed, competing accepts for that invite are ignored by the session UI. */
    @Synchronized
    fun acknowledgeAcceptance(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, inviteIdHex: String, acceptedSessionDht: String): Result<WidgetPublicSignal> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.AcceptAck, inviteIdHex = inviteIdHex, peerSessionDht = acceptedSessionDht)
    }

    /** Explicitly locks acknowledgement to the exact hash read, never to "whatever is there now". */
    @Synchronized
    fun acknowledgeRead(hostMainDht: String, elementId: String, sourceHash: String, program: WidgetProgram, acceptedEventHashHex: String): Result<WidgetPublicSignal> = runCatching {
        require(program.onlineMode == WidgetOnlineMode.Public) { "widget did not declare online = public" }
        publishEvent(hostMainDht, elementId, sourceHash, program, WidgetNetworkEventKind.ReadAck, acceptedEventHashHex = acceptedEventHashHex)
    }

    private fun publishEvent(
        hostMainDht: String,
        elementId: String,
        sourceHash: String,
        program: WidgetProgram,
        kind: WidgetNetworkEventKind,
        inputs: List<WidgetInputValue> = emptyList(),
        text: String = "",
        inviteIdHex: String = "",
        peerSessionDht: String = "",
        acceptedEventHashHex: String = "",
        actionIdHex: String = "",
        actionStep: Int = 0,
        actionCount: Int = 0,
        randomCommitHashHex: String = "",
        randomRevealHex: String = "",
        rollIndex: Int = 0,
        rollSides: Int = 0,
    ): WidgetPublicSignal {
        require(hostMainDht.isNotBlank()) { "widget owner is missing" }
        require(sourceHash.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid widget source hash" }
        cleanupExpiredSessions()
        val normalizedSource = sourceHash.lowercase()
        val instance = widgetInstanceIdHex(hostMainDht, elementId, normalizedSource)
        require(allowRate(publishRate, instance, MAX_PUBLICATIONS_PER_MINUTE)) { "widget network rate limit reached" }
        val session = ensureSession(hostMainDht, instance, normalizedSource)
        val sequence = session.sequence + 1L
        val event = WidgetNetworkEvent(
            kind = kind,
            widgetSourceHashHex = normalizedSource,
            widgetInstanceIdHex = instance,
            sessionIdHex = session.sessionId,
            sequence = sequence,
            previousEventHashHex = session.previousHash,
            createdAtMs = System.currentTimeMillis(),
            inputs = inputs.map { it.copy() },
            text = text,
            inviteIdHex = inviteIdHex,
            peerSessionDht = peerSessionDht,
            acceptedEventHashHex = acceptedEventHashHex,
            actionIdHex = actionIdHex, actionStep = actionStep, actionCount = actionCount,
            randomCommitHashHex = randomCommitHashHex, randomRevealHex = randomRevealHex,
            rollIndex = rollIndex, rollSides = rollSides,
        )
        val bytes = event.canonicalBytes()
        val eventHash = event.eventHashHex()
        val textSlot = if (text.isNotEmpty()) {
            val slot = VeilWidgetLimits.WIDGET_SESSION_MESSAGE_FIRST_SUBKEY + (session.messageCursor % VeilWidgetLimits.MAX_NETWORK_TEXT_MESSAGES)
            session.messageCursor = (session.messageCursor + 1) % VeilWidgetLimits.MAX_NETWORK_TEXT_MESSAGES
            slot
        } else null
        val location = if (kind == WidgetNetworkEventKind.Text) {
            textSlot ?: error("text event has no message slot")
        } else {
            val count = VeilWidgetLimits.WIDGET_SESSION_EVENT_LAST_SUBKEY - VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + 1
            VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + (((sequence - 1L) % count).toInt())
        }
        client.writeStore(session.storeId, location, bytes)
        // If an input submission also carries text, keep the exact same canonical event in the
        // five-message ring so a receiver never needs to scan the larger event journal for chat.
        if (textSlot != null && textSlot != location) client.writeStore(session.storeId, textSlot, bytes)
        session.sequence = sequence
        session.previousHash = eventHash
        writeSessionHeader(session)
        replaceSession(session)

        val signal = WidgetPublicSignal(normalizedSource, instance, session.recordKey, sequence, eventHash, kind)
        val signalBytes = WidgetPublicSignalCodec.encode(signal)
        client.publishServiceRequest(
            intendedHostMainDht = hostMainDht,
            serviceIdHex = PUBLIC_SERVICE_ID,
            manifestHashHex = PUBLIC_MANIFEST_HASH,
            instanceIdHex = instance,
            payload = signalBytes,
            delegationAllowed = true,
            spectatorsAllowed = true,
            ttlSeconds = VeilWidgetLimits.PUBLIC_WIDGET_REQUEST_TTL_SECONDS,
        )
        if (program.mailboxCopy && hostMainDht != ownMainDht()) {
            val expires = System.currentTimeMillis() / 1000L + VeilWidgetLimits.RETIRED_SESSION_KEEP_MS / 1000L
            client.sendMessage(recipientMainDht = hostMainDht, payload = signalBytes, expiresAtSeconds = expires, preferDirect = false)
        }
        rememberSignal(signal)
        logger("widget network: published ${kind.name.lowercase()} seq=$sequence instance=${instance.take(12)} session=${short(session.recordKey)}")
        return signal
    }

    /**
     * Handles the public one-hour ServiceRequest. The sender identity comes from the daemon wrapper;
     * the payload itself never gets to assert an identity.
     */
    fun receivePublicServiceRequest(raw: JSONObject) {
        val payload = runCatching { Base64.decode(raw.getString("payload_base64"), Base64.DEFAULT) }.getOrNull() ?: return
        val signal = WidgetPublicSignalCodec.decode(payload) ?: return
        val requester = raw.optString("requester_main_dht").ifBlank { raw.optString("sender_main_dht") }
        rememberSignal(signal)
        if (requester.isBlank()) return
        processSignalForOwnedWidget(signal, requester, "public")
    }

    /**
     * Classifies an authenticated direct/mailbox pointer. RetryLater means the pointer was valid
     * but its referenced DHT event was not readable yet, so an offline inbox sweep should keep it.
     */
    fun receiveMailboxPointer(payload: ByteArray, authenticatedSourceMainDht: String): WidgetMailboxDisposition {
        val signal = WidgetPublicSignalCodec.decode(payload) ?: return WidgetMailboxDisposition.NotWidget
        rememberSignal(signal)
        if (authenticatedSourceMainDht.isBlank()) return WidgetMailboxDisposition.Consumed
        return processSignalForOwnedWidget(signal, authenticatedSourceMainDht, "mailbox")
    }

    fun recentSignals(widgetInstanceIdHex: String): List<WidgetPublicSignal> =
        recentSignals[widgetInstanceIdHex]?.toList().orEmpty()

    /** True only for a Session DHT created by this local Weave account. */
    @Synchronized
    fun isOwnSessionDht(recordKey: String): Boolean =
        recordKey.isNotBlank() && loadSessions().any { it.recordKey == recordKey }

    /**
     * Receiver-side validation against the locally compiled widget definition. Modified senders
     * may publish arbitrary bytes; only events passing these local rules reach widget source.
     */
    fun validateRuntimeEvent(
        program: WidgetProgram,
        expectedSourceHash: String,
        expectedInstanceId: String,
        signal: WidgetPublicSignal,
        event: WidgetNetworkEvent,
    ): Boolean {
        if (program.onlineMode != WidgetOnlineMode.Public) return false
        val source = expectedSourceHash.lowercase()
        if (event.widgetSourceHashHex != source || signal.widgetSourceHashHex != source) return false
        if (event.widgetInstanceIdHex != expectedInstanceId || signal.widgetInstanceIdHex != expectedInstanceId) return false
        if (event.sequence != signal.sequence || event.eventHashHex() != signal.eventHashHex || event.kind != signal.kind) return false
        if (event.createdAtMs > System.currentTimeMillis() + 24L * 60L * 60L * 1000L) return false
        if (event.kind == WidgetNetworkEventKind.Inputs) {
            if (event.actionIdHex.isNotBlank()) {
                if (!event.actionIdHex.matches(Regex("^[0-9a-f]{32}$"))) return false
                if (event.actionCount !in 1..VeilWidgetLimits.MAX_NETWORK_ACTION_STEPS || event.actionStep !in 1..event.actionCount) return false
            }
            val defs = program.inputs.associateBy { it.id }
            if (event.inputs.isEmpty() || event.inputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) return false
            val seen = mutableSetOf<String>()
            event.inputs.forEach { value ->
                val def = defs[value.inputId] ?: return false
                if (!seen.add(value.inputId)) return false
                when (def.kind) {
                    WidgetInputKind.Number -> if (value.numberValue !in def.minValue..def.maxValue) return false
                    WidgetInputKind.Button -> if (value.numberValue != 0) return false
                }
            }
        }
        when (event.kind) {
            WidgetNetworkEventKind.RandomCommit -> if (!event.randomCommitHashHex.matches(Regex("^[0-9a-f]{64}$"))) return false
            WidgetNetworkEventKind.RandomReveal -> if (!event.randomRevealHex.matches(Regex("^[0-9a-f]{64}$"))) return false
            WidgetNetworkEventKind.RandomRoll -> if (event.rollIndex <= 0 || event.rollSides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) return false
            else -> Unit
        }
        if (event.text.isNotEmpty()) runCatching { validateWidgetPlainText(event.text) }.getOrElse { return false }
        return true
    }

    /** Never reads more than the five fixed text slots from one Session DHT. */
    fun readRecentTextMessages(sessionDht: String, sourceHash: String, instanceId: String): List<WidgetNetworkEvent> = runCatching {
        require(allowRate(readRate, instanceId, MAX_REMOTE_READS_PER_MINUTE)) { "widget read rate limit reached" }
        val locations = (VeilWidgetLimits.WIDGET_SESSION_MESSAGE_FIRST_SUBKEY..VeilWidgetLimits.WIDGET_SESSION_MESSAGE_LAST_SUBKEY).toList()
        val values = client.readPublicStore(sessionDht, locations, true).optJSONArray("values") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until minOf(values.length(), VeilWidgetLimits.MAX_NETWORK_TEXT_MESSAGES)) {
                val event = CommentChain.decodeValue(values.optJSONObject(i))?.let(WidgetNetworkEventCodec::decode) ?: continue
                if (event.text.isNotEmpty() && event.widgetSourceHashHex == sourceHash.lowercase() && event.widgetInstanceIdHex == instanceId) add(event)
            }
        }.sortedBy { it.sequence }.takeLast(VeilWidgetLimits.MAX_NETWORK_TEXT_MESSAGES)
    }.getOrDefault(emptyList())

    /** Read exactly the signalled record and hash-pin it; later sender rewrites cannot change this result. */
    fun readSignalledEvent(signal: WidgetPublicSignal): WidgetNetworkEvent? {
        return runCatching { readSignalledEventChecked(signal) }.getOrNull()
    }

    private fun readSignalledEventChecked(signal: WidgetPublicSignal): WidgetNetworkEvent? {
        require(allowRate(readRate, signal.widgetInstanceIdHex, MAX_REMOTE_READS_PER_MINUTE)) { "widget read rate limit reached" }
        if (signal.kind == WidgetNetworkEventKind.Text) {
            // Text is a five-slot ring. Find the exact hash rather than trusting a slot derived from sequence.
            val locations = (VeilWidgetLimits.WIDGET_SESSION_MESSAGE_FIRST_SUBKEY..VeilWidgetLimits.WIDGET_SESSION_MESSAGE_LAST_SUBKEY).toList()
            val values = client.readPublicStore(signal.sessionDht, locations, true).optJSONArray("values") ?: return null
            for (i in 0 until values.length()) {
                val event = CommentChain.decodeValue(values.optJSONObject(i))?.let(WidgetNetworkEventCodec::decode) ?: continue
                if (event.sequence == signal.sequence && event.eventHashHex() == signal.eventHashHex) return event
            }
            return null
        }

        val count = VeilWidgetLimits.WIDGET_SESSION_EVENT_LAST_SUBKEY - VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + 1
        val slot = VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + (((signal.sequence - 1L) % count).toInt())
        val values = client.readPublicStore(signal.sessionDht, listOf(slot), true).optJSONArray("values")
        val event = CommentChain.decodeValue(values?.optJSONObject(0))?.let(WidgetNetworkEventCodec::decode) ?: return null
        if (event.sequence != signal.sequence || event.eventHashHex() != signal.eventHashHex) return null
        return event
    }

    private fun processSignalForOwnedWidget(signal: WidgetPublicSignal, authenticatedSourceMainDht: String, transport: String): WidgetMailboxDisposition {
        val ownedList = loadOwnedData().toMutableList()
        val index = ownedList.indexOfFirst { it.instanceId == signal.widgetInstanceIdHex && it.sourceHash == signal.widgetSourceHashHex }
        // It is a valid Widget Networking pointer, but it is not for a currently-owned widget.
        // Consume it rather than leaving unrelated/obsolete pointers in the identity inbox forever.
        if (index < 0) return WidgetMailboxDisposition.Consumed
        val owned = ownedList[index]
        val lockKey = "${signal.sessionDht}|${signal.sequence}"
        if (owned.seenLocks.containsKey(lockKey)) return WidgetMailboxDisposition.Consumed

        val event = readSignalledEvent(signal) ?: return WidgetMailboxDisposition.RetryLater
        if (!validateIncomingEvent(owned, signal, event)) return WidgetMailboxDisposition.Consumed
        // Lock the exact first accepted hash for this sender-DHT + sequence. A later rewrite is ignored.
        owned.seenLocks[lockKey] = signal.eventHashHex
        while (owned.seenLocks.size > MAX_LOCKS_PER_WIDGET) owned.seenLocks.remove(owned.seenLocks.keys.first())
        ownedList[index] = owned
        saveOwnedData(ownedList)
        updatePublisherSummary(owned, event, signal.eventHashHex)
        logger("widget network: accepted $transport event seq=${signal.sequence} from=${short(authenticatedSourceMainDht)} instance=${signal.widgetInstanceIdHex.take(12)}")
        return WidgetMailboxDisposition.Consumed
    }

    private fun validateIncomingEvent(owned: OwnedData, signal: WidgetPublicSignal, event: WidgetNetworkEvent): Boolean {
        if (event.widgetSourceHashHex != owned.sourceHash || event.widgetInstanceIdHex != owned.instanceId) return false
        if (event.widgetSourceHashHex != signal.widgetSourceHashHex || event.widgetInstanceIdHex != signal.widgetInstanceIdHex) return false
        if (event.sequence != signal.sequence || event.eventHashHex() != signal.eventHashHex || event.kind != signal.kind) return false
        if (event.createdAtMs > System.currentTimeMillis() + 24L * 60L * 60L * 1000L) return false
        if (event.kind == WidgetNetworkEventKind.Inputs) {
            val defs = owned.inputs.associateBy { it.id }
            if (event.inputs.isEmpty() || event.inputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) return false
            val seen = mutableSetOf<String>()
            event.inputs.forEach { value ->
                val def = defs[value.inputId] ?: return false
                if (!seen.add(value.inputId)) return false
                when (def.kind) {
                    WidgetInputKind.Number -> if (value.numberValue !in def.minValue..def.maxValue) return false
                    WidgetInputKind.Button -> if (value.numberValue != 0) return false
                }
            }
        }
        when (event.kind) {
            WidgetNetworkEventKind.RandomCommit -> if (!event.randomCommitHashHex.matches(Regex("^[0-9a-f]{64}$"))) return false
            WidgetNetworkEventKind.RandomReveal -> if (!event.randomRevealHex.matches(Regex("^[0-9a-f]{64}$"))) return false
            WidgetNetworkEventKind.RandomRoll -> if (event.rollIndex <= 0 || event.rollSides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) return false
            else -> Unit
        }
        if (event.text.isNotEmpty()) runCatching { validateWidgetPlainText(event.text) }.getOrElse { return false }
        return true
    }

    private fun validateOutgoingInputs(program: WidgetProgram, values: List<WidgetInputValue>) {
        if (values.isEmpty()) return
        val defs = program.inputs.associateBy { it.id }
        val seen = mutableSetOf<String>()
        values.forEach { value ->
            val def = defs[value.inputId] ?: error("undeclared widget input ${value.inputId}")
            require(seen.add(value.inputId)) { "duplicate widget input ${value.inputId}" }
            when (def.kind) {
                WidgetInputKind.Number -> require(value.numberValue in def.minValue..def.maxValue) { "widget input ${value.inputId} outside declared range" }
                WidgetInputKind.Button -> require(value.numberValue == 0) { "button inputs do not carry values" }
            }
        }
    }

    @Synchronized
    private fun ensureSession(hostMainDht: String, instanceId: String, sourceHash: String): Session {
        val contextKey = sha256Hex("$hostMainDht|$instanceId".toByteArray())
        val sessions = loadSessions().toMutableList()
        val now = System.currentTimeMillis()
        sessions.firstOrNull { it.contextKey == contextKey && it.state == WidgetSessionState.Active }?.let { active ->
            if (now - active.createdAt < VeilWidgetLimits.SESSION_ROTATE_AFTER_MS) return active
            active.state = WidgetSessionState.Retired
            active.stateChangedAt = now
            writeSessionHeader(active)
            replaceSession(active, sessions)
        }

        val id = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val suffix = id.take(12)
        val created = client.createStore("weave_widget_session_$suffix", VeilWidgetLimits.WIDGET_SESSION_DHT_SUBKEYS).getJSONObject("store")
        val session = Session(
            contextKey, instanceId, sourceHash, hostMainDht,
            created.getString("store_id"), created.getString("record_key"), id,
            now, WidgetSessionState.Active, now, 0L, "0".repeat(64), 0,
        )
        writeSessionHeader(session)
        sessions += session
        saveSessions(sessions)
        logger("widget network: new rotating Session DHT ${short(session.recordKey)}")
        return session
    }

    private fun writeSessionHeader(session: Session) {
        val header = WidgetSessionHeader(
            session.sourceHash, session.instanceId, session.sessionId, session.state,
            session.createdAt, session.stateChangedAt, session.sequence, session.previousHash,
        )
        client.writeStore(session.storeId, 0, WidgetSessionHeaderCodec.encode(header))
    }

    @Synchronized
    fun cleanupExpiredSessions() {
        val sessions = loadSessions().toMutableList()
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = sessions.listIterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.state == WidgetSessionState.Active && now - session.createdAt >= VeilWidgetLimits.SESSION_ROTATE_AFTER_MS) {
                session.state = WidgetSessionState.Retired; session.stateChangedAt = now; writeSessionHeader(session); iterator.set(session); changed = true
            }
            if (session.state == WidgetSessionState.Retired && now - session.stateChangedAt >= VeilWidgetLimits.RETIRED_SESSION_KEEP_MS) {
                val tombstone = WidgetTombstoneCodec.encode(session.instanceId, now)
                // Overwrite every slot this session could have used. This is intentionally a rare,
                // background cleanup operation after the one-week retention window.
                val usedEventSlots = minOf(session.sequence, (VeilWidgetLimits.WIDGET_SESSION_EVENT_LAST_SUBKEY - VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + 1).toLong()).toInt()
                repeat(usedEventSlots) { offset -> client.writeStore(session.storeId, VeilWidgetLimits.WIDGET_SESSION_EVENT_FIRST_SUBKEY + offset, tombstone) }
                for (slot in VeilWidgetLimits.WIDGET_SESSION_MESSAGE_FIRST_SUBKEY..VeilWidgetLimits.WIDGET_SESSION_MESSAGE_LAST_SUBKEY) client.writeStore(session.storeId, slot, tombstone)
                client.writeStore(session.storeId, VeilWidgetLimits.WIDGET_SESSION_TOMBSTONE_SUBKEY, tombstone)
                session.state = WidgetSessionState.Tombstone; session.stateChangedAt = now
                writeSessionHeader(session)
                iterator.remove(); changed = true
                logger("widget network: expired Session DHT ${short(session.recordKey)} wiped; ${WidgetTombstoneCodec.MESSAGE}")
            }
        }
        if (changed) saveSessions(sessions)
    }

    private fun updatePublisherSummary(owned: OwnedData, event: WidgetNetworkEvent, eventHash: String) {
        val current = runCatching {
            val values = client.readStore(owned.storeId, listOf(DATA_SUMMARY_SUBKEY), false).optJSONArray("values")
            CommentChain.decodeValue(values?.optJSONObject(0))?.let(WidgetDataSummaryCodec::decode)
        }.getOrNull() ?: WidgetDataSummary(owned.sourceHash, owned.instanceId, 0, 0, emptyMap(), emptyList())
        if (eventHash in current.recentEventHashes) return
        val tallies = current.tallies.toMutableMap()
        if (event.kind == WidgetNetworkEventKind.Inputs) {
            event.inputs.forEach { value ->
                val def = owned.inputs.firstOrNull { it.id == value.inputId } ?: return@forEach
                val key = if (def.kind == WidgetInputKind.Button) "${value.inputId}=button" else "${value.inputId}=${value.numberValue}"
                tallies[key] = (tallies[key] ?: 0L) + 1L
            }
        }
        // Text remains plain text in the sender Session DHT. The durable Data DHT records only
        // the accepted-event count/hash; it never treats text as markup/code.
        val recent = (current.recentEventHashes + eventHash).takeLast(64)
        val next = WidgetDataSummary(owned.sourceHash, owned.instanceId, System.currentTimeMillis(), current.acceptedEvents + 1L, tallies, recent)
        client.writeStore(owned.storeId, DATA_SUMMARY_SUBKEY, WidgetDataSummaryCodec.encode(next))
    }

    private fun rememberSignal(signal: WidgetPublicSignal) {
        val queue = recentSignals.computeIfAbsent(signal.widgetInstanceIdHex) { ArrayDeque() }
        synchronized(queue) {
            if (queue.none { it.sessionDht == signal.sessionDht && it.sequence == signal.sequence && it.eventHashHex == signal.eventHashHex }) queue.add(signal)
            while (queue.size > MAX_RECENT_SIGNALS) queue.removeFirst()
        }
    }

    private fun replaceSession(session: Session, list: MutableList<Session> = loadSessions().toMutableList()) {
        val index = list.indexOfFirst { it.storeId == session.storeId }
        if (index >= 0) list[index] = session else list += session
        saveSessions(list)
    }

    private fun allowRate(map: ConcurrentHashMap<String, ArrayDeque<Long>>, key: String, limit: Int): Boolean {
        val now = System.currentTimeMillis()
        val q = map.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() >= 60_000L) q.removeFirst()
            if (q.size >= limit) return false
            q.add(now)
            return true
        }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun loadSessions(): List<Session> {
        if (!vault.attached) return emptyList()
        val root = runCatching { JSONObject(vault.getText(SESSION_INDEX_KEY) ?: "{}") }.getOrDefault(JSONObject())
        val array = root.optJSONArray("sessions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val state = runCatching { WidgetSessionState.valueOf(o.optString("state")) }.getOrNull() ?: continue
                val session = Session(
                    contextKey=o.optString("context"),instanceId=o.optString("instance"),sourceHash=o.optString("source"),hostMainDht=o.optString("host"),
                    storeId=o.optString("store_id"),recordKey=o.optString("record_key"),sessionId=o.optString("session_id"),
                    createdAt=o.optLong("created"),state=state,stateChangedAt=o.optLong("changed"),sequence=o.optLong("sequence"),previousHash=o.optString("previous").ifBlank{"0".repeat(64)},messageCursor=o.optInt("message_cursor")
                )
                if (session.contextKey.isNotBlank() && session.storeId.isNotBlank() && session.recordKey.isNotBlank() && session.sessionId.matches(Regex("^[0-9a-f]{32}$"))) add(session)
            }
        }
    }

    private fun saveSessions(sessions: List<Session>) {
        if (!vault.attached) return
        val array=JSONArray();sessions.forEach{s->array.put(JSONObject().put("context",s.contextKey).put("instance",s.instanceId).put("source",s.sourceHash).put("host",s.hostMainDht).put("store_id",s.storeId).put("record_key",s.recordKey).put("session_id",s.sessionId).put("created",s.createdAt).put("state",s.state.name).put("changed",s.stateChangedAt).put("sequence",s.sequence).put("previous",s.previousHash).put("message_cursor",s.messageCursor))}
        vault.putText(SESSION_INDEX_KEY,JSONObject().put("v",1).put("sessions",array).toString())
    }

    private fun loadOwnedData(): List<OwnedData> {
        if (!vault.attached) return emptyList()
        val root=runCatching{JSONObject(vault.getText(DATA_INDEX_KEY)?:"{}")}.getOrDefault(JSONObject());val a=root.optJSONArray("items")?:JSONArray()
        return buildList{
            for(i in 0 until a.length()){
                val o=a.optJSONObject(i)?:continue;val instance=o.optString("instance");val source=o.optString("source");val store=o.optString("store_id");val record=o.optString("record_key")
                if(!instance.matches(Regex("^[0-9a-f]{64}$"))||!source.matches(Regex("^[0-9a-f]{64}$"))||store.isBlank()||record.isBlank())continue
                val defs=mutableListOf<WidgetInputDefinition>();val da=o.optJSONArray("inputs")?:JSONArray();for(j in 0 until da.length()){val d=da.optJSONObject(j)?:continue;val kind=runCatching{WidgetInputKind.valueOf(d.optString("kind"))}.getOrNull()?:continue;defs+=WidgetInputDefinition(d.optString("id"),kind,d.optInt("min"),d.optInt("max"))}
                val locks=linkedMapOf<String,String>();val lo=o.optJSONObject("locks")?:JSONObject();lo.keys().forEach{key->val hash=lo.optString(key);if(hash.matches(Regex("^[0-9a-f]{64}$")))locks[key]=hash}
                add(OwnedData(instance,source,store,record,defs,o.optBoolean("mail_copy"),locks))
            }
        }
    }

    private fun saveOwnedData(items: List<OwnedData>) {
        if(!vault.attached)return
        val a=JSONArray();items.forEach{item->
            val defs=JSONArray();item.inputs.forEach{d->defs.put(JSONObject().put("id",d.id).put("kind",d.kind.name).put("min",d.minValue).put("max",d.maxValue))}
            val locks=JSONObject();item.seenLocks.forEach{(k,v)->locks.put(k,v)}
            a.put(JSONObject().put("instance",item.instanceId).put("source",item.sourceHash).put("store_id",item.storeId).put("record_key",item.recordKey).put("inputs",defs).put("mail_copy",item.mailboxCopy).put("locks",locks))
        }
        vault.putText(DATA_INDEX_KEY,JSONObject().put("v",1).put("items",a).toString())
    }
}
