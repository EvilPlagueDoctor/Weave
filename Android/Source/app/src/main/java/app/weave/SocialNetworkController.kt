package app.weave

import android.content.Context
import android.util.Base64
import app.weave.backbone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PROFILE_STORE_NAME = "weave-profile-page-v1"
private const val PROFILE_SUBKEY = 0

/** How often to sweep the mailbox for notices that arrived while the app was closed. */
private const val INBOX_DRAIN_MS = 60_000L

/** Messages handled per sweep, so a large backlog cannot stall the worker loop. */
private const val INBOX_DRAIN_LIMIT = 50
private const val PEER_REFRESH_MS = 20_000L
private const val GOSSIP_INTERVAL_MS = 12_000L
private const val GROUP_GOSSIP_INTERVAL_MS = 8_000L
private val RECONNECT_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L, 15_000L, 30_000L)
private const val GOSSIP_SUMMARY_LOG_REPEAT_MS = 60_000L
private const val DIAGNOSTIC_LOG_MAX_BYTES = 1024 * 1024
private const val DIAGNOSTIC_LOG_KEEP_BYTES = 700 * 1024
private const val PROFILE_MAX_BYTES = 4 * 1024 * 1024
private val LOG_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")

data class SocialProfileRow(val hint: ProfileHint, val openable: Boolean)

data class SocialUiState(
    val status: String = "Starting…",
    /** Transport/auth session is currently usable. mainDht may remain populated during a reconnect. */
    val connected: Boolean = false,
    val mainDht: String = "",
    val profileRoot: String = "",
    /** True only when subkey 0 contains this identity's current published profile record. */
    val published: Boolean = false,
    val discoveryName: String = "",
    val discoveryDescription: String = "",
    val featuresText: String = "",
    val recent: List<SocialProfileRow> = emptyList(),
    val searchResults: List<ScoredProfile> = emptyList(),
    val nameQuery: String = "",
    val query: String = "",
    val positiveMainDht: String = "",
    val negativeMainDht: String = "",
    val avoidance: Float = .75f,
    val commonPenalty: Float = .20f,
    val stuffingPenalty: Float = .25f,
    val novelty: Float = .10f,
    val peers: Int = 0,
    val verified: Int = 0,
    val gossipSent: Long = 0,
    val gossipReceived: Long = 0,
    val clusterText: String = "",
    val debugLog: String = "",
    /** True while a publish is in flight, so the UI can disable the control. */
    val publishing: Boolean = false,
    /** Bumped on every comment change so screens re-read the local store. */
    val commentRevision: Long = 0,
    /** This profile's own rule for its comment section. */
    val commentPolicy: CommentPolicy = CommentPolicy.Open,
)

class SocialNetworkController(context: Context) {
    private val appContext = context.applicationContext
    private val diagnosticLogFile = File(appContext.filesDir, "weave-diagnostics.log")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val legacyPrefs = appContext.getSharedPreferences("weave_discovery", Context.MODE_PRIVATE)
    private val vault = PrivateVault.get(appContext)

    /** Local read model for comments. Owned here so the sync path and the UI share one instance. */
    val comments: CommentStore = LocalCommentStore(appContext)
    val groups: GroupStore = GroupStore(appContext)
    private var commentNetwork: CommentNetwork? = null
    private var groupRuntime: GroupRuntime? = null
    private val _ui = MutableStateFlow(SocialUiState())
    val ui: StateFlow<SocialUiState> = _ui.asStateFlow()
    val groupEventsV2: GroupEventStoreV2 = GroupEventStoreV2(appContext) { message -> log(message) }
    val groupCustodyV2: GroupCustodyStoreV2 = GroupCustodyStoreV2(appContext) { message -> log(message) }
    val groupTrustV2: GroupTrustContinuityStoreV2 = GroupTrustContinuityStoreV2(appContext) { message -> log(message) }

    private var daemon: DaemonClient? = null
    private var messageSubscription: Long? = null
    private var groupServiceSubscription: Long? = null
    private var widgetServiceSubscription: Long? = null
    private var widgetNetwork: WidgetNetworkManager? = null
    private var networkJob: Job? = null
    private val cache = KnowledgeCache()
    // Concurrent collections rather than plain ones: the worker loop, every incoming message,
    // each profile verification and each comment operation are separate coroutines on
    // Dispatchers.IO, so these are genuinely touched from several threads at once. The same
    // oversight in KnowledgeCache is what crashed the app on the Home screen.
    private val fullRecords = ConcurrentHashMap<String, ProfilePageRecord>()
    private val profileDocuments = ConcurrentHashMap<String, String>()
    private val knownPeers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queryReplyAt = ConcurrentHashMap<String, Long>()
    private var profileStoreId: String? = null
    private var ownRecord: ProfilePageRecord? = null
    private var activeIntent: DiscoveryIntent? = null
    private var nextRequestId = 1L
    private val gossipSentCount = AtomicLong(0)
    private val gossipReceivedCount = AtomicLong(0)
    private val groupHintStamp = ConcurrentHashMap<String, Long>()
    private val gossipSummaryDigestBySource = ConcurrentHashMap<String, String>()
    private val gossipSummaryLogAtBySource = ConcurrentHashMap<String, Long>()
    private val groupHintInFlight = ConcurrentHashMap.newKeySet<String>()
    private val loggedGroupGeneration = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGroupGossipSendLogMs = 0L
    @Volatile private var lastDebugUiRefreshMs = 0L

    init {
        val tail = readPersistentLogTail()
        if (tail.isNotBlank()) _ui.value = _ui.value.copy(debugLog = tail)
    }

    fun start() {
        // The worker is a reconnect loop rather than a one-shot connection. Android's Binder
        // service may stay alive while the native daemon logs out, restarts, or signs into a
        // different VeilKnit profile, so a running Weave must notice that change itself.
        if (networkJob?.isActive == true) return
        networkJob = scope.launch {
            var reconnectFailures = 0
            var reconnectDelayMs = 0L
            while (isActive) {
                try {
                    resetNetworkIdentityState()
                    runCatching { daemon?.close("reconnect loop replacing previous client") }
                    daemon = DaemonClient(appContext)

                    updateStatus("Connecting to VeilKnit daemon…")
                    daemon!!.connect { updateStatus(it) }
                    loadPrivateMetadata()
                    val daemonIdentity = daemon!!.activeDaemonIdentity()
                    daemonIdentity?.let {
                        log("connected to daemon profile ${short(it.profileId)} instance ${short(it.daemonInstanceId)}")
                    }

                    val identity = daemon!!.identity()
                    val mainDht = identity.getString("main_dht")
                    _ui.value = _ui.value.copy(
                        connected = true,
                        mainDht = mainDht,
                        status = "Connected; preparing profile-page DHT…"
                    )
                    WeaveDiagnostics.event(appContext, "NETWORK_CONNECTED", "main_dht=${short(mainDht)}")
                    log("Weave account active: main_dht=$mainDht")
                    groupEventsV2.bind(daemon!!, mainDht)
                    groupEventsV2.cleanup()
                    ensureProfileStore()
                    groupRuntime = GroupRuntime(
                        client = daemon!!,
                        store = groups,
                        profileStoreId = { profileStoreId },
                        ownMainDht = { _ui.value.mainDht },
                        eventStoreV2 = groupEventsV2,
                        custodyStoreV2 = groupCustodyV2,
                        trustStoreV2 = groupTrustV2,
                        custodyCandidates = { knownPeers.toList() },
                        logger = ::log,
                    )
                    // Queue durable event recovery before any slower directory/profile DHT work.
                    // It runs on its own bounded workers and cannot stall normal startup.
                    groupRuntime?.recoverPendingContentV2()
                    widgetNetwork = WidgetNetworkManager(appContext, daemon!!, { _ui.value.mainDht }, ::log)
                    subscribeGroupOpenIntake()
                    subscribeWidgetPublicNetwork()
                    scope.launch { runCatching { widgetNetwork?.cleanupExpiredSessions() }.onFailure { log("widget session cleanup: ${it.message}") } }
                    val advertisedGroups = groupRuntime?.publishMyDirectory() ?: 0
                    log("group directory ready: $advertisedGroups owned/claimed branch advertisement(s)")
                    logGroupResponsibilities("startup")
                    readOwnRecord()
                    subscribeMessages()
                    refreshPeers()
                    groupRuntime?.recoverCustodyV2()
                    gossipGroups()
                    updateSnapshots()
                    setStatusQuiet(healthLine())
                    drainInbox(force = true)
                    reconnectFailures = 0
                    reconnectDelayMs = 0L

                    var lastPeerRefresh = 0L
                    var lastGossip = 0L
                    var lastGroupGossip = 0L
                    var lastInboxDrain = System.currentTimeMillis()
                    while (isActive) {
                        // A different daemon_instance_id means every old session token is dead.
                        // A different profile_id additionally means all DHT/cache state belongs
                        // to another VeilKnit identity. Either case is a clean reconnect.
                        if (daemon?.daemonIdentityChanged() != false) {
                            throw DaemonSessionChangedException()
                        }

                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastPeerRefresh >= PEER_REFRESH_MS) {
                            refreshPeers()
                            groupRuntime?.recoverCustodyV2()
                            lastPeerRefresh = nowMs
                        }
                        if (nowMs - lastInboxDrain >= INBOX_DRAIN_MS) {
                            drainInbox(force = false); lastInboxDrain = nowMs
                        }
                        if (nowMs - lastGossip >= GOSSIP_INTERVAL_MS) {
                            gossipSummary(); lastGossip = nowMs
                        }
                        if (nowMs - lastGroupGossip >= GROUP_GOSSIP_INTERVAL_MS) {
                            gossipGroups(); lastGroupGossip = nowMs
                        }
                        activeIntent?.let(::runLocalSearch)
                        updateSnapshots()
                        setStatusQuiet(healthLine())
                        delay(1000)
                    }
                } catch (changed: DaemonSessionChangedException) {
                    if (!isActive) break
                    reconnectFailures = 0
                    reconnectDelayMs = 1_000L
                    _ui.value = _ui.value.copy(connected = false)
                    log("VeilKnit daemon account or process changed; reconnecting as the active account")
                    WeaveDiagnostics.event(appContext, "NETWORK_RECONNECT", "reason=daemon_identity_changed")
                    updateStatus("VeilKnit account changed; reconnecting…")
                } catch (t: Throwable) {
                    if (!isActive) break
                    reconnectFailures++
                    reconnectDelayMs = RECONNECT_BACKOFF_MS[minOf(reconnectFailures - 1, RECONNECT_BACKOFF_MS.lastIndex)]
                    val detail = t.message.orEmpty()
                    _ui.value = _ui.value.copy(connected = false)
                    WeaveDiagnostics.event(
                        appContext,
                        "NETWORK_RECONNECT",
                        "reason=${t::class.java.simpleName}:${detail.replace('\n', ' ').take(180)} attempt=$reconnectFailures"
                    )
                    log("network connection lost: $detail")
                    log("reconnect backoff: attempt=$reconnectFailures delay=${reconnectDelayMs}ms")
                    updateStatus("Connection lost; retrying…")
                } finally {
                    messageSubscription?.let { id -> runCatching { daemon?.unsubscribe(id) } }
                    groupServiceSubscription?.let { id -> runCatching { daemon?.unsubscribe(id) } }
                    widgetServiceSubscription?.let { id -> runCatching { daemon?.unsubscribe(id) } }
                    messageSubscription = null
                    groupServiceSubscription = null
                    widgetServiceSubscription = null
                    widgetNetwork = null
                    groupRuntime?.shutdown()
                    groupRuntime = null
                    runCatching { daemon?.close("network loop cleanup/reconnect") }
                    daemon = null
                }

                if (isActive) delay(reconnectDelayMs.coerceAtLeast(1_000L))
            }
        }
    }

    private class DaemonSessionChangedException : RuntimeException()

    /**
     * Forget only network/account-derived state. Editable local content is intentionally not
     * deleted here; moving that content into profile-scoped encrypted storage is a separate
     * persistence concern and must not be implemented by silently deleting the old files.
     */
    private fun resetNetworkIdentityState() {
        messageSubscription = null
        groupServiceSubscription = null
        widgetServiceSubscription = null
        widgetNetwork = null
        commentNetwork = null
        groupRuntime?.shutdown()
        groupRuntime = null
        profileStoreId = null
        ownRecord = null
        activeIntent = null
        fullRecords.clear()
        profileDocuments.clear()
        knownPeers.clear()
        queryReplyAt.clear()
        groupHintStamp.clear()
        groupHintInFlight.clear()
        loggedGroupGeneration.clear()
        gossipSummaryDigestBySource.clear()
        gossipSummaryLogAtBySource.clear()
        lastGroupGossipSendLogMs = 0L
        cache.clear()
        val previous = _ui.value
        // Keep the last confirmed account identity and local UI metadata through a transient
        // reconnect. Clearing mainDht here used to destroy WeaveShell and its navigation stack,
        // which is why the editor could suddenly disappear back to Me.
        _ui.value = previous.copy(
            connected = false,
            recent = emptyList(),
            searchResults = emptyList(),
            peers = 0,
            verified = 0,
            gossipSent = 0,
            gossipReceived = 0,
            clusterText = "",
            publishing = false,
        )
    }

    /** Tears the connection down and dials again. Used by the retry control on the gate. */
    fun restart() {
        messageSubscription?.let { runCatching { daemon?.unsubscribe(it) } }
        groupServiceSubscription?.let { runCatching { daemon?.unsubscribe(it) } }
        widgetServiceSubscription?.let { runCatching { daemon?.unsubscribe(it) } }
        messageSubscription = null
        groupServiceSubscription = null
        widgetServiceSubscription = null
        widgetNetwork = null
        groupRuntime?.shutdown()
        groupRuntime = null
        networkJob?.cancel()
        networkJob = null
        runCatching { daemon?.close("manual retry") }
        daemon = null
        _ui.value = _ui.value.copy(connected = false, status = "Reconnecting to VeilKnit daemon…")
        WeaveDiagnostics.event(appContext, "NETWORK_RESTART", "manual retry requested")
        start()
    }

    fun stop() {
        messageSubscription?.let { daemon?.unsubscribe(it) }
        groupServiceSubscription?.let { daemon?.unsubscribe(it) }
        widgetServiceSubscription?.let { daemon?.unsubscribe(it) }
        messageSubscription = null
        groupServiceSubscription = null
        widgetServiceSubscription = null
        widgetNetwork = null
        groupRuntime?.shutdown()
        groupRuntime = null
        networkJob?.cancel()
        networkJob = null
        daemon?.close("controller stop")
        daemon = null
        _ui.value = _ui.value.copy(connected = false)
        // Cancel the work, not the scope: cancelling the scope itself would make this
        // controller permanently unusable and break any later restart().
        scope.coroutineContext.cancelChildren()
    }

    fun setDiscoveryName(value: String) { _ui.value = _ui.value.copy(discoveryName = value.take(80)) }
    fun setDiscoveryDescription(value: String) {
        _ui.value = _ui.value.copy(discoveryDescription = value.take(1500)); persistMetadata()
    }
    fun setFeatures(value: String) { _ui.value = _ui.value.copy(featuresText = value.take(2000)); persistMetadata() }
    fun setNameQuery(value: String) { _ui.value = _ui.value.copy(nameQuery = value.take(120)) }
    fun setQuery(value: String) { _ui.value = _ui.value.copy(query = value.take(300)) }
    fun setPositive(value: String) { _ui.value = _ui.value.copy(positiveMainDht = value.trim()) }
    fun setNegative(value: String) { _ui.value = _ui.value.copy(negativeMainDht = value.trim()) }
    fun setAvoidance(value: Float) { _ui.value = _ui.value.copy(avoidance = value); persistMetadata() }
    fun setCommonPenalty(value: Float) { _ui.value = _ui.value.copy(commonPenalty = value); persistMetadata() }
    fun setStuffingPenalty(value: Float) { _ui.value = _ui.value.copy(stuffingPenalty = value); persistMetadata() }
    fun setNovelty(value: Float) { _ui.value = _ui.value.copy(novelty = value); persistMetadata() }

    fun publishProfile(profileText: String, fallbackName: String) = scope.launch {
        _ui.value = _ui.value.copy(publishing = true)
        try {
            require(profileText.encodeToByteArray().size <= PROFILE_MAX_BYTES) { "Profile page exceeds ${PROFILE_MAX_BYTES / 1024 / 1024} MiB prototype limit" }
            val decoded = ProfileCodec.decodeText(profileText)
            val validation = ProfileCodec.validate(decoded)
            require(validation.ok) { "Profile is not valid VSPF: ${validation.message}" }

            val d = daemon ?: error("Daemon is not connected")
            val state = _ui.value
            val storeId = profileStoreId ?: error("Profile store is not ready")
            val root = state.profileRoot.ifBlank { error("Profile root is not ready") }
            val bytes = profileText.encodeToByteArray()
            val sha = sha256Hex(bytes)
            updateStatus("Uploading decorated profile page…")
            val blob = d.uploadBlob("application/x-weave-vspf-text;version=4", bytes)
            val blobId = blob.getString("blob_id")
            try {
                val features = state.featuresText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(64)
                val name = state.discoveryName.trim().ifBlank { fallbackName.trim() }.take(80)
                val record = ProfilePageRecord(
                    mainDht = state.mainDht,
                    profileRootDht = root,
                    generation = (ownRecord?.generation ?: 0) + 1,
                    updatedAt = nowSeconds(),
                    name = name,
                    description = state.discoveryDescription.take(1500),
                    features = features,
                    profileBlobId = blobId,
                    profileBlobRoot = blob.getString("root_record_key"),
                    profileSha256Hex = blob.optString("sha256_hex", sha),
                    profileBytes = blob.optLong("total_bytes", bytes.size.toLong()),
                    vspfVersion = 4,
                    commentPolicy = state.commentPolicy,
                )
                require(record.profileSha256Hex.equals(sha, ignoreCase = true)) { "Daemon blob hash did not match local profile hash" }
                d.writeStore(storeId, PROFILE_SUBKEY, record.toJson().toString().encodeToByteArray())
                d.registerAppRoot(root)
                val oldBlob = ownRecord?.profileBlobId?.takeIf { it.isNotBlank() && it != blobId }
                ownRecord = record
                fullRecords[record.mainDht] = record
                profileDocuments[record.mainDht] = profileText
                cache.upsert(record.toHint(nowSeconds(), VerificationState.DHT_VERIFIED), "self", nowSeconds())
                _ui.value = _ui.value.copy(published = true)
                oldBlob?.let { runCatching { d.deleteBlob(it) }.onFailure { log("old profile blob cleanup: ${it.message}") } }
                persistMetadata()
                log("published profile page generation ${record.generation}; blob=${short(record.profileBlobRoot)} sha=${record.profileSha256Hex.take(16)} signature=${record.signature().compactHex()}")
                updateStatus("Profile page published")
                gossipProfileAnnounce(record)
                updateSnapshots()
            } catch (t: Throwable) {
                runCatching { d.deleteBlob(blobId) }
                throw t
            }
        } catch (t: Throwable) {
            log("publish failed: ${t.message}")
            updateStatus("Publish failed: ${t.message}")
        } finally {
            _ui.value = _ui.value.copy(publishing = false)
        }
    }


    /**
     * Withdraw the current public profile record and every network blob Weave can still
     * identify as belonging to that publication. The owned profile store itself remains: it
     * is the stable app root, but its public profile subkey becomes empty.
     */
    fun unpublishProfile(media: LocalMediaStore) = scope.launch {
        try {
            val d = daemon ?: error("Daemon is not connected")
            val storeId = profileStoreId ?: error("Profile store is not ready")
            updateStatus("Unpublishing profile…")

            // Readers already treat an empty value as no profile record. Clear the pointer
            // first so a partially completed cleanup fails closed.
            d.writeStore(storeId, PROFILE_SUBKEY, ByteArray(0))

            val old = ownRecord
            old?.profileBlobId?.takeIf { it.isNotBlank() }?.let { blobId ->
                runCatching { d.deleteBlob(blobId) }
                    .onFailure { log("profile blob unpublish cleanup: ${it.message}") }
            }
            media.networkBlobIds().forEach { blobId ->
                runCatching { d.deleteBlob(blobId) }
                    .onFailure { log("media blob unpublish cleanup: ${it.message}") }
            }
            media.clearNetworkMappings()

            ownRecord = null
            fullRecords.remove(_ui.value.mainDht)
            profileDocuments.remove(_ui.value.mainDht)
            cache.remove(_ui.value.mainDht)
            _ui.value = _ui.value.copy(published = false)
            updateStatus("Profile unpublished")
            updateSnapshots()
        } catch (t: Throwable) {
            log("unpublish failed: ${t.message}")
            updateStatus("Unpublish failed: ${t.message}")
        }
    }

    /** A compact report for the restored one-line search's data button. */
    fun librarianReport(query: String): String {
        val terms = tokenize(query).distinct()
        return cache.librarianReport(terms, gossipSentCount.get(), gossipReceivedCount.get())
    }

    /** Import a DHT blob by record key, verifying the hash supplied by the daemon metadata. */
    suspend fun downloadMediaByRecordKey(rootRecordKey: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val d = daemon ?: return@withContext null
        // People commonly copy a DHT key out of a larger label/link. Accept either the raw
        // VLD0 record key or a pasted dht:// / surrounding-text form without guessing hashes.
        val pasted = rootRecordKey.trim()
        val normalizedRecordKey = Regex("VLD0:[A-Za-z0-9_-]+").find(pasted)?.value
            ?: pasted.removePrefix("dht://").trim()
        if (normalizedRecordKey.isBlank()) return@withContext null
        runCatching {
            val (meta, bytes) = d.downloadBlob(normalizedRecordKey, maxBytes)
            val expected = meta.optString("sha256_hex")
            if (expected.isNotBlank() && !sha256Hex(bytes).equals(expected, ignoreCase = true)) {
                error("DHT image hash did not match its blob metadata")
            }
            bytes
        }.onFailure { log("DHT image import failed: ${it.message}") }.getOrNull()
    }

    fun refreshNow() = scope.launch { refreshPeers(); groupRuntime?.recoverCustodyV2(); updateSnapshots() }
    fun gossipNow() = scope.launch { gossipSummary(); ownRecord?.let(::gossipProfileAnnounce); updateSnapshots() }

    /**
     * Small home-page discovery sample: mostly MinHash similarity, with a stable half-hour jitter
     * so Home does not become a rigid ranking. Following/self identities are excluded by the caller.
     */
    fun suggestedPeople(excluding: Set<String> = emptySet(), limit: Int = 3): List<ProfileHint> {
        val state = _ui.value
        val ownSignature = ownRecord?.signature() ?: MinHash.fromFeatures(
            extractFeatures(
                state.discoveryDescription,
                state.featuresText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() },
            )
        )
        val epoch = System.currentTimeMillis() / (30L * 60L * 1000L)
        val candidates = cache.all().asSequence()
            .map { it.hint }
            .filter { it.mainDht.isNotBlank() && it.mainDht != state.mainDht && it.mainDht !in excluding }
            .map { hint ->
                val similarity = ownSignature.similarity(hint.minhash)
                val mixed = hint.mainDht.hashCode().toLong() xor (epoch * -7046029254386353131L)
                val jitter = ((mixed xor (mixed ushr 33)) and 0xffffL).toFloat() / 65535f
                Triple(hint, similarity, similarity * 0.84f + jitter * 0.16f)
            }
            .sortedByDescending { it.third }
            .toList()
        return candidates.take(limit.coerceIn(0, 12)).map { it.first }
    }

    fun search() = scope.launch {
        val state = _ui.value
        val positive = state.positiveMainDht.takeIf { it.isNotBlank() }?.let { cache.get(it)?.hint }
        val negative = state.negativeMainDht.takeIf { it.isNotBlank() }?.let { cache.get(it)?.hint }
        val intent = DiscoveryIntent(
            positive = positive?.let { listOf(WeightedSignature(it.minhash, 1f)) } ?: emptyList(),
            negative = negative?.let { listOf(WeightedSignature(it.minhash, 1f)) } ?: emptyList(),
            // The restored UI intentionally has one search line. Use that one value for
            // both name matching and lexical/content matching instead of preserving the
            // old hidden second field in the search intent.
            nameQuery = state.query.trim().takeIf { it.isNotEmpty() },
            positiveTerms = tokenize(state.query),
            avoidanceStrength = state.avoidance,
            commonTermPenalty = state.commonPenalty,
            stuffingPenalty = state.stuffingPenalty,
            noveltyWeight = state.novelty,
        )
        activeIntent = intent
        runLocalSearch(intent)
        val request = GossipMessage.SimilarityQuery(nextRequestId++, intent, 6)
        sendGossipToSample(request.toJson().toString().encodeToByteArray(), 6)
        log("search started: name=${intent.nameQuery ?: "none"} terms=${intent.positiveTerms} positive=${positive?.mainDht ?: "none"} negative=${negative?.mainDht ?: "none"}")
    }

    fun moreLike(mainDht: String) { setPositive(mainDht); setNegative(""); search() }
    fun avoidLike(mainDht: String) { setNegative(mainDht); search() }
    fun clearExamples() { setPositive(""); setNegative("") }

    fun profileText(mainDht: String): String? = profileDocuments[mainDht]


    /** Published/profile name for a group authority. Falls back to the caller's key in UI. */
    fun groupAuthorityName(mainDht: String): String? {
        if (mainDht.isBlank()) return null
        if (mainDht == _ui.value.mainDht) {
            return ownRecord?.name?.takeIf { it.isNotBlank() }
                ?: _ui.value.discoveryName.takeIf { it.isNotBlank() }
        }
        return fullRecords[mainDht]?.name?.takeIf { it.isNotBlank() }
            ?: cache.get(mainDht)?.hint?.name?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------
    // Groups / moderation branches
    // ---------------------------------------------------------------------

    fun noteGroupBranchSelected(branch: GroupBranchPointer) {
        val owner = groupAuthorityName(branch.ownerMainDht) ?: short(branch.ownerMainDht)
        log("group branch selected: group=${short(branch.groupId)} kind=${branch.kind.name} owner=$owner branch=${short(branch.branchId)}")
    }

    fun publishGroup(
        group: GroupRecord,
        pulse: GroupPulse,
        onPublished: (GroupRecord) -> Unit = {},
    ) = scope.launch {
        try {
            val runtime = groupRuntime ?: error("Group runtime is not ready")
            val published = runtime.publishOriginal(group, pulse)
            withContext(Dispatchers.Main.immediate) { onPublished(published) }
            val advertised = groups.directoryEntriesForSelf(_ui.value.mainDht).size
            log("published group ${published.name}: ${short(published.rootRecordKey)}; profile group directory=$advertised")
            logGroupResponsibilities("group-published")
            gossipGroups()
        } catch (t: Throwable) {
            log("group publish failed: ${t.message}")
        }
    }

    fun refreshGroupBranch(
        branch: GroupBranchPointer,
        onLoaded: (GroupRecord, GroupPulse?) -> Unit = { _, _ -> },
    ) = scope.launch {
        try {
            val loaded = groupRuntime?.refreshBranch(branch) ?: error("Group branch unavailable")
            groupTrustV2.ensureWatch(branch.groupId, branch.branchId)
            val generation = loaded.second?.generation ?: -1L
            val key = "${branch.groupId}|${branch.branchId}"
            val previous = loggedGroupGeneration.put(key, generation)
            if (previous == null) {
                log("group branch loaded: ${loaded.first.name} ${branch.kind.name} owner=${groupAuthorityName(branch.ownerMainDht) ?: short(branch.ownerMainDht)} pulse_generation=$generation")
            } else if (generation > previous) {
                log("group branch updated: ${loaded.first.name} ${branch.kind.name} pulse_generation=$previous->$generation")
            }
            withContext(Dispatchers.Main.immediate) { onLoaded(loaded.first, loaded.second) }
        } catch (t: Throwable) {
            log("group branch refresh failed: ${t.message}")
        }
    }

    fun claimGroup(
        groupId: String,
        onClaimed: (GroupBranchHeader) -> Unit = {},
        onResult: (Boolean, String) -> Unit = { _, _ -> },
    ) = scope.launch {
        try {
            val claim = groupRuntime?.claim(groupId) ?: error("Group runtime is not ready")
            withContext(Dispatchers.Main.immediate) {
                onClaimed(claim)
                onResult(true, "Claim moderation branch created. The Original branch remains unchanged.")
            }
            log("claimed group ${claim.group.name} (${short(groupId)}) as branch ${short(claim.branchId)}; selected locally")
            logGroupResponsibilities("group-claimed")
            gossipGroups()
        } catch (t: Throwable) {
            log("group claim failed: ${t.message}")
            withContext(Dispatchers.Main.immediate) {
                onResult(false, t.message ?: "Could not create Claim moderation branch")
            }
        }
    }

    fun groupAuthorityContinuity(groupId: String): GroupAuthorityContinuityV2? {
        val group = groups.byId(groupId) ?: return null
        val selected = groups.selectedBranch(groupId) ?: group.rootRecordKey.takeIf { it.isNotBlank() }?.let {
            GroupBranchPointer(
                groupId = groupId,
                branchId = GroupBranchNetwork.originalBranchId(groupId),
                branchRoot = it,
                ownerMainDht = group.ownerId,
                kind = GroupBranchKind.Original,
                creatorRoot = it,
                updatedAt = group.updatedAt,
            )
        } ?: return null
        val header = groups.selectedHeader(groupId)
        val authorities = linkedSetOf(selected.ownerMainDht).apply {
            header?.moderators?.mapTo(this) { it.moderatorMainDht }
        }
        val now = System.currentTimeMillis()
        val pulse = groups.pulse(groupId, selected.branchId)
        val recentConversationActivity = pulse.conversations.any { conversation ->
            conversation.lastActivity > 0L && now - conversation.lastActivity <= 48L * 60L * 60L * 1000L
        }
        val hasActivity = groups.pendingFor(groupId).isNotEmpty() || recentConversationActivity
        return groupTrustV2.continuity(
            groupId = groupId,
            branchId = selected.branchId,
            authorityPeers = authorities,
            ownMainDht = _ui.value.mainDht,
            hasPendingOrRecentActivity = hasActivity,
            now = now,
        )
    }

    fun resolveGroupLink(raw: String, onResolved: (GroupRecord?) -> Unit = {}) = scope.launch {
        val result = runCatching {
            groupRuntime?.resolveGroupLink(raw) ?: error("Group runtime is not ready")
        }
        result.onFailure { log("group link resolve failed: ${it.message}") }
        withContext(Dispatchers.Main.immediate) { onResolved(result.getOrNull()) }
    }

    fun claimGroupLink(raw: String, onResult: (String) -> Unit = {}) = scope.launch {
        val result = runCatching {
            groupRuntime?.claimLink(raw) ?: error("Group runtime is not ready")
        }
        result.onSuccess { claim ->
            val advertised = groupRuntime?.publishMyDirectory() ?: 0
            log("claimed group ${claim.group.name} as branch ${short(claim.branchId)}; directory entries=$advertised; profile_published=${_ui.value.published}")
            logGroupResponsibilities("group-claimed")
            gossipGroups()
            val message = if (_ui.value.published) {
                "Group is now claimed. Your moderation branch is advertised on your published profile."
            } else {
                "Group is now claimed locally. Publish your profile so other people can discover your moderation branch."
            }
            withContext(Dispatchers.Main.immediate) { onResult(message) }
        }.onFailure { error ->
            log("group link claim failed: ${error.message}")
            withContext(Dispatchers.Main.immediate) {
                onResult(error.message ?: "Could not claim that group")
            }
        }
    }

    fun createGroupPost(
        groupId: String,
        title: String,
        body: String,
        authorName: String,
        media: LocalMediaStore,
        image: LocalMediaStore.Stored? = null,
        audio: LocalMediaStore.AudioStored? = null,
        onDone: (Boolean, String?) -> Unit = { _, _ -> },
    ) = scope.launch {
        log("group post upload started: group=${short(groupId)} image=${image != null} audio=${audio != null} title=${title.take(48)}")
        val result = runCatching {
            var thumbnailBase64: String? = null
            val fullMedia = mutableListOf<WeaveObjectRef>()
            if (image != null) {
                val bytes = media.bytesFor(image.contentHash)
                    ?: error("Selected image is no longer available")
                val blob = uploadMedia("image/webp", bytes)
                    ?: error("Could not upload the post image")
                val recordKey = blob.optString("root_record_key")
                if (recordKey.isBlank()) error("The daemon uploaded an image but returned no record key")
                val remoteHash = blob.optString("sha256_hex").ifBlank { sha256Hex(bytes) }
                log("group post image uploaded: bytes=${bytes.size} record=${short(recordKey)}")
                blob.optString("blob_id").takeIf { it.isNotBlank() }?.let { blobId ->
                    media.recordBlob(image.contentHash, blobId, recordKey)
                }
                thumbnailBase64 = media.thumbnailBase64For(image.contentHash)
                fullMedia += WeaveObjectRef(
                    objectId = remoteHash,
                    recordKey = recordKey,
                    subkey = 0,
                    type = WeaveObjectType.Image,
                )
            }
            if (audio != null) {
                val bytes = media.audioBytesFor(audio.contentHash)
                    ?: error("Selected audio is no longer available")
                val blob = uploadMedia("audio/mp4", bytes)
                    ?: error("Could not upload the post audio")
                val recordKey = blob.optString("root_record_key")
                if (recordKey.isBlank()) error("The daemon uploaded audio but returned no record key")
                val remoteHash = blob.optString("sha256_hex").ifBlank { sha256Hex(bytes) }
                log("group post audio uploaded: bytes=${bytes.size} record=${short(recordKey)}")
                blob.optString("blob_id").takeIf { it.isNotBlank() }?.let { blobId ->
                    media.recordBlob(audio.contentHash, blobId, recordKey)
                }
                fullMedia += WeaveObjectRef(
                    objectId = remoteHash,
                    recordKey = recordKey,
                    subkey = 0,
                    type = WeaveObjectType.Audio,
                )
            }
            val conversationId = groupRuntime?.createGroupPost(
                groupId = groupId,
                title = title,
                body = body,
                authorName = authorName,
                thumbnailBase64 = thumbnailBase64,
                fullMedia = fullMedia,
            ) ?: error("Group runtime is not ready")
            log("group post submitted: group=${short(groupId)} conversation=${short(conversationId)}")
            gossipGroups()
            conversationId
        }.onFailure { log("group post creation failed: ${it.message}") }
        withContext(Dispatchers.Main.immediate) {
            onDone(result.isSuccess, result.getOrNull())
        }
    }

    fun postGroupComment(
        groupId: String,
        conversationId: String,
        body: String,
        authorName: String,
        onDone: (Boolean) -> Unit = {},
    ) = scope.launch {
        val ok = runCatching {
            groupRuntime?.postComment(groupId, conversationId, body, authorName)
                ?: error("Group runtime is not ready")
        }.onFailure { log("group comment failed: ${it.message}") }.isSuccess
        withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }

    fun refreshGroupPost(
        groupId: String,
        conversationId: String,
        onLoaded: (GroupPostDetail?) -> Unit = {},
    ) = scope.launch {
        val post = runCatching {
            groupRuntime?.hydratePost(groupId, conversationId)
        }.onFailure { log("group post refresh failed: ${it.message}") }.getOrNull()
        withContext(Dispatchers.Main.immediate) { onLoaded(post) }
    }

    fun pinGroupComment(groupId: String, postId: String, pinned: Boolean) = scope.launch {
        runCatching {
            groupRuntime?.setPinned(groupId, postId, pinned) ?: error("Group runtime is not ready")
        }.onFailure { log("group pin failed: ${it.message}") }
    }

    fun setGroupFeatured(groupId: String, slot: FeaturedSlot) = scope.launch {
        runCatching {
            groupRuntime?.setFeatured(groupId, slot) ?: error("Group runtime is not ready")
        }.onFailure { log("group featured update failed: ${it.message}") }
    }

    fun submitGroupConversation(groupId: String, conversation: WeaveObjectRef, title: String) = scope.launch {
        runCatching {
            groupRuntime?.submitConversation(groupId, conversation, title) ?: error("Group runtime is not ready")
        }.onFailure { log("group conversation submit failed: ${it.message}") }
    }

    fun submitGroupPost(
        groupId: String,
        conversationId: String,
        message: WeaveMessage,
        messageRef: WeaveObjectRef,
    ) = scope.launch {
        runCatching {
            groupRuntime?.submitPost(groupId, conversationId, message, messageRef) ?: error("Group runtime is not ready")
        }.onFailure { log("group post submit failed: ${it.message}") }
    }

    fun reportGroupPost(groupId: String, postId: String, postHash: String, reason: String) = scope.launch {
        runCatching {
            groupRuntime?.submitReport(groupId, postId, postHash, reason) ?: error("Group runtime is not ready")
        }.onFailure { log("group report failed: ${it.message}") }
    }

    fun requestGroupJoin(groupId: String, message: String = "") = scope.launch {
        runCatching {
            groupRuntime?.requestJoin(groupId, message) ?: error("Group runtime is not ready")
        }.onFailure { log("group join request failed: ${it.message}") }
    }

    fun moderateGroupPost(
        groupId: String,
        postId: String,
        conversationId: String,
        postHash: String,
        state: GroupPostState,
        messageRef: WeaveObjectRef?,
        reason: String = "",
    ) = scope.launch {
        runCatching {
            groupRuntime?.moderatePost(groupId, postId, conversationId, postHash, state, messageRef, reason)
                ?: error("Group runtime is not ready")
        }.onFailure { log("group moderation action failed: ${it.message}") }
    }

    fun loadGroupMessage(
        ref: WeaveObjectRef,
        onLoaded: (WeaveMessage?) -> Unit,
    ) = scope.launch {
        val message = runCatching {
            groupRuntime?.loadMessage(ref) ?: error("Group runtime is not ready")
        }.onFailure { log("group post load failed: ${it.message}") }.getOrNull()
        withContext(Dispatchers.Main.immediate) { onLoaded(message) }
    }

    fun modifyGroupPost(
        groupId: String,
        conversationId: String,
        sourceRef: WeaveObjectRef,
        title: String,
        body: String,
        onDone: (Boolean, String) -> Unit = { _, _ -> },
    ) = scope.launch {
        val result = runCatching {
            groupRuntime?.modifyPostForBranch(groupId, conversationId, sourceRef, title, body)
                ?: error("Group runtime is not ready")
        }
        result.onFailure { log("group curated-post edit failed: ${it.message}") }
        if (result.isSuccess) gossipGroups()
        withContext(Dispatchers.Main.immediate) {
            onDone(
                result.isSuccess,
                result.exceptionOrNull()?.message ?: "Curated branch copy updated; the source post was not changed.",
            )
        }
    }

    fun deleteGroupPost(
        groupId: String,
        conversationId: String,
        sourceRef: WeaveObjectRef,
        reason: String,
        onDone: (Boolean, String) -> Unit = { _, _ -> },
    ) = scope.launch {
        val result = runCatching {
            groupRuntime?.removePostForBranch(groupId, conversationId, sourceRef, reason)
                ?: error("Group runtime is not ready")
        }
        result.onFailure { log("group post removal failed: ${it.message}") }
        if (result.isSuccess) gossipGroups()
        withContext(Dispatchers.Main.immediate) {
            onDone(
                result.isSuccess,
                result.exceptionOrNull()?.message ?: "Post removed from this moderation branch.",
            )
        }
    }

    fun banGroupAuthor(
        groupId: String,
        authorMainDht: String,
        onDone: (Boolean, String) -> Unit = { _, _ -> },
    ) = scope.launch {
        val result = runCatching {
            groupRuntime?.banAuthor(groupId, authorMainDht) ?: error("Group runtime is not ready")
        }
        result.onFailure { log("group author ban failed: ${it.message}") }
        if (result.isSuccess) gossipGroups()
        withContext(Dispatchers.Main.immediate) {
            onDone(
                result.isSuccess,
                result.exceptionOrNull()?.message ?: "Author banned on this moderation branch.",
            )
        }
    }

    fun loadGroupCuratorPosts(
        groupId: String,
        onLoaded: (List<GroupCuratorPost>) -> Unit,
    ) = scope.launch {
        val posts = runCatching {
            groupRuntime?.curatorPosts(groupId).orEmpty()
        }.onFailure { log("group curator view failed: ${it.message}") }.getOrDefault(emptyList())
        withContext(Dispatchers.Main.immediate) { onLoaded(posts) }
    }

    fun grantGroupModerator(groupId: String, grant: GroupModeratorGrant) = scope.launch {
        runCatching {
            groupRuntime?.grantModerator(groupId, grant) ?: error("Group runtime is not ready")
        }.onFailure { log("group moderator grant failed: ${it.message}") }
    }

    fun resolveGroupModerationTask(taskId: String, approve: Boolean) = scope.launch {
        runCatching {
            groupRuntime?.resolveModerationTask(taskId, approve) ?: error("Group runtime is not ready")
        }.onFailure { log("group moderation resolution failed: ${it.message}") }
    }

    fun installPrivateGroupIntakeKey(groupId: String, key: ByteArray) {
        groupRuntime?.installPrivateGroupIntakeKey(groupId, key)
    }

    // ---------------------------------------------------------------------
    // Media blobs
    //
    // Images live in their own blobs rather than inside the profile payload, so a page with
    // five pictures is five independent fetches that can be skipped, deferred or abandoned
    // instead of one payload that must arrive whole.
    // ---------------------------------------------------------------------

    /** Ensures this published widget instance has its long-lived publisher-owned Data DHT. */
    suspend fun ensureWidgetDataDht(elementId: String, sourceHash: String, program: WidgetProgram): String? =
        withContext(Dispatchers.IO) {
            runCatching { widgetNetwork?.ensurePublisherDataDht(elementId, sourceHash, program) }
                .onFailure { log("widget Data DHT: ${it.message}") }
                .getOrNull()
        }

    /** Narrow host passed to an activated online widget. It exposes no raw DHT/mailbox operations. */
    fun widgetNetworkHost(ownerMainDht: String, element: Element, program: WidgetProgram): WidgetNetworkHost? {
        if (program.onlineMode != WidgetOnlineMode.Public) return null
        val sourceHash = element.widgetSourceHash.lowercase()
        if (ownerMainDht.isBlank() || sourceHash.isBlank()) return null
        val instanceId = widgetInstanceIdHex(ownerMainDht, element.id, sourceHash)

        data class PendingAction(
            val hashes: MutableSet<String>,
            val event: WidgetHostEvent,
        )

        return object : WidgetNetworkHost {
            private val seenSignals = ConcurrentHashMap.newKeySet<String>()
            private val pendingInputs = ConcurrentHashMap<String, Pair<WidgetPublicSignal, WidgetNetworkEvent>>()
            private val pendingActions = ConcurrentHashMap<String, List<Pair<WidgetPublicSignal, WidgetNetworkEvent>>>()
            private val incomingActionPieces = ConcurrentHashMap<String, ConcurrentHashMap<Int, Pair<WidgetPublicSignal, WidgetNetworkEvent>>>()
            private val pendingInvites = ConcurrentHashMap<String, Pair<WidgetPublicSignal, WidgetNetworkEvent>>()
            private val outgoingByHash = ConcurrentHashMap<String, WidgetHostEvent>()
            private val outgoingActions = ConcurrentHashMap<String, PendingAction>()
            private val hashToAction = ConcurrentHashMap<String, String>()
            private val openInviteSessions = ConcurrentHashMap<String, String>()
            private val queue = java.util.concurrent.ConcurrentLinkedQueue<WidgetHostEvent>()
            private val secureRandom = java.security.SecureRandom()

            @Volatile private var waitingInviteId: String = ""
            @Volatile private var waitingOwnSessionDht: String = ""
            @Volatile private var activePeerSessionDht: String = ""
            @Volatile private var activeOwnSessionDht: String = ""
            @Volatile private var pairInviteId: String = ""
            @Volatile private var myPlayer: Int = 0
            @Volatile private var closed: Boolean = false

            private val actionLock = Any()
            private var actionOpen = false
            private val actionBuffer = mutableListOf<List<WidgetInputValue>>()
            private var actionInputIds: Set<String> = emptySet()

            private val randomLock = Any()
            private var ownRandomSecret = ""
            private var ownRandomCommit = ""
            private var peerRandomCommit = ""
            private var peerRandomReveal = ""
            private var ownRevealPublished = false
            private var ownCommitPublished = false
            private var sharedRandomSeed = ""
            private var randomFirstPlayer = 0
            private var randomReadyEmitted = false
            private var nextRollIndex = 1

            private fun signalKey(signal: WidgetPublicSignal): String =
                "${signal.sessionDht}|${signal.sequence}|${signal.eventHashHex}"

            private fun opaqueToken(signal: WidgetPublicSignal): String =
                sha256Hex("widget-runtime|${signalKey(signal)}".toByteArray()).take(32)

            private fun otherPlayer(): Int = if (myPlayer == 1) 2 else if (myPlayer == 2) 1 else 0

            private fun randomOrderFor(ownDht: String, peerDht: String): Int {
                val ownHash = sha256Hex(ownDht.toByteArray(Charsets.UTF_8))
                val peerHash = sha256Hex(peerDht.toByteArray(Charsets.UTF_8))
                val ownKey = ownHash.take(4)
                val peerKey = peerHash.take(4)
                return if (ownKey < peerKey || (ownKey == peerKey && ownHash < peerHash)) myPlayer else otherPlayer()
            }

            private fun randomCommit(secretHex: String): String = sha256Hex(
                "weave-widget-random-commit-v1\u0000$pairInviteId\u0000$secretHex".toByteArray(Charsets.UTF_8)
            )

            private fun rollValue(seedHex: String, index: Int, sides: Int): Int {
                val h = sha256Hex("weave-widget-roll-v1\u0000$seedHex\u0000$index\u0000$sides".toByteArray(Charsets.UTF_8))
                val n = h.take(8).toLong(16)
                return (n % sides.toLong()).toInt() + 1
            }

            private fun startRandomNegotiation() {
                if (closed || myPlayer !in 1..2 || activeOwnSessionDht.isBlank() || activePeerSessionDht.isBlank() || pairInviteId.isBlank()) return
                synchronized(randomLock) {
                    if (ownRandomSecret.isNotBlank()) return
                    val bytes = ByteArray(32).also(secureRandom::nextBytes)
                    ownRandomSecret = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    ownRandomCommit = randomCommit(ownRandomSecret)
                    randomFirstPlayer = randomOrderFor(activeOwnSessionDht, activePeerSessionDht)
                }
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.publishRandomCommit(ownerMainDht, element.id, sourceHash, program, ownRandomCommit)
                        .onSuccess { synchronized(randomLock) { ownCommitPublished = true }; maybePublishReveal() }
                        .onFailure { log("widget random commitment failed: ${it.message}") }
                }
            }

            private fun maybePublishReveal() {
                val shouldReveal = synchronized(randomLock) {
                    if (ownRevealPublished || !ownCommitPublished || peerRandomCommit.isBlank()) false
                    else if (myPlayer == randomFirstPlayer) true
                    else peerRandomReveal.isNotBlank()
                }
                if (!shouldReveal) return
                synchronized(randomLock) { ownRevealPublished = true }
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.publishRandomReveal(ownerMainDht, element.id, sourceHash, program, ownRandomSecret)
                        .onFailure { synchronized(randomLock) { ownRevealPublished = false }; log("widget random reveal failed: ${it.message}") }
                }
            }

            private fun maybeFinishRandom() {
                val event = synchronized(randomLock) {
                    if (randomReadyEmitted || !ownRevealPublished || peerRandomReveal.isBlank() || peerRandomCommit.isBlank()) return@synchronized null
                    if (randomCommit(peerRandomReveal) != peerRandomCommit) {
                        log("widget random reveal rejected: commitment mismatch")
                        return@synchronized null
                    }
                    val p1 = if (myPlayer == 1) ownRandomSecret else peerRandomReveal
                    val p2 = if (myPlayer == 2) ownRandomSecret else peerRandomReveal
                    sharedRandomSeed = sha256Hex("weave-widget-random-seed-v1\u0000$pairInviteId\u0000$p1\u0000$p2".toByteArray(Charsets.UTF_8))
                    randomReadyEmitted = true
                    WidgetHostEvent(WidgetHostEventKind.RandomReady, myPlayer = myPlayer, randomFirstPlayer = randomFirstPlayer)
                }
                if (event != null) queue.add(event)
            }

            private fun establishPair(player: Int, ownSessionDht: String, peerSessionDht: String, inviteId: String) {
                myPlayer = player
                activeOwnSessionDht = ownSessionDht
                activePeerSessionDht = peerSessionDht
                pairInviteId = inviteId
                queue.add(WidgetHostEvent(WidgetHostEventKind.SessionReady, myPlayer = myPlayer))
                startRandomNegotiation()
            }

            override fun send(values: List<WidgetInputValue>, text: String) {
                if (closed) return
                synchronized(actionLock) {
                    if (actionOpen) {
                        if (text.isNotEmpty() || values.isEmpty()) {
                            log("widget action: only declared input steps may be buffered")
                            return
                        }
                        if (actionBuffer.size >= VeilWidgetLimits.MAX_NETWORK_ACTION_STEPS) {
                            log("widget action: step limit reached")
                            return
                        }
                        val ids = values.map { it.inputId }.toSet()
                        if (actionBuffer.isEmpty()) actionInputIds = ids
                        if (ids != actionInputIds) {
                            log("widget action: all steps must use the same input names")
                            return
                        }
                        actionBuffer += values.map { it.copy(numberExpression = "") }
                        return
                    }
                }
                scope.launch {
                    if (closed) return@launch
                    val manager = widgetNetwork ?: return@launch
                    manager.submit(ownerMainDht, element.id, sourceHash, program, inputs = values, text = text)
                        .onSuccess { signal ->
                            if (closed) return@onSuccess
                            if (values.isNotEmpty()) outgoingByHash[signal.eventHashHex] = WidgetHostEvent(
                                kind = WidgetHostEventKind.Committed,
                                inputs = values.map { it.copy(numberExpression = "") }, text = text,
                                myPlayer = myPlayer, eventPlayer = myPlayer,
                            )
                        }
                        .onFailure { log("widget network submission rejected: ${it.message}") }
                }
            }

            override fun beginAction() {
                if (closed || activePeerSessionDht.isBlank()) return
                synchronized(actionLock) {
                    if (actionOpen) return
                    actionOpen = true
                    actionBuffer.clear(); actionInputIds = emptySet()
                }
            }

            override fun endAction() {
                if (closed) return
                val steps = synchronized(actionLock) {
                    if (!actionOpen) return
                    actionOpen = false
                    val copy = actionBuffer.map { step -> step.map { it.copy() } }
                    actionBuffer.clear(); actionInputIds = emptySet(); copy
                }
                if (steps.isEmpty()) return
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.submitAction(ownerMainDht, element.id, sourceHash, program, steps)
                        .onSuccess { publication ->
                            val hashes = publication.signals.map { it.eventHashHex }.toMutableSet()
                            val event = WidgetHostEvent(WidgetHostEventKind.ActionCommitted, actionSteps = steps, myPlayer = myPlayer, eventPlayer = myPlayer)
                            outgoingActions[publication.actionIdHex] = PendingAction(hashes, event)
                            hashes.forEach { hashToAction[it] = publication.actionIdHex }
                        }
                        .onFailure { log("widget grouped action rejected: ${it.message}") }
                }
            }

            override fun roll(sides: Int) {
                if (closed || sides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) return
                val index: Int
                val first: Int
                val seed: String
                synchronized(randomLock) {
                    if (sharedRandomSeed.isBlank()) return
                    index = nextRollIndex
                    first = randomFirstPlayer
                    val expected = if (index % 2 == 1) first else if (first == 1) 2 else 1
                    if (myPlayer != expected) return
                    seed = sharedRandomSeed
                }
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.publishRandomRoll(ownerMainDht, element.id, sourceHash, program, index, sides)
                        .onSuccess {
                            synchronized(randomLock) { if (nextRollIndex == index) nextRollIndex++ }
                            queue.add(WidgetHostEvent(WidgetHostEventKind.Roll, myPlayer = myPlayer, eventPlayer = myPlayer, randomFirstPlayer = first, rollIndex = index, rollSides = sides, rollValue = rollValue(seed, index, sides)))
                        }
                        .onFailure { log("widget dice roll failed: ${it.message}") }
                }
            }

            override fun openInvitation() {
                if (closed) return
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.openInvitation(ownerMainDht, element.id, sourceHash, program)
                        .onSuccess { (inviteId, signal) -> openInviteSessions[inviteId] = signal.sessionDht }
                        .onFailure { log("widget invitation rejected: ${it.message}") }
                }
            }

            override fun accept(token: String) {
                if (closed) return
                pendingActions.remove(token)?.let { parts ->
                    scope.launch {
                        val manager = widgetNetwork ?: return@launch
                        var ok = true
                        parts.forEach { pair -> if (manager.acknowledgeRead(ownerMainDht, element.id, sourceHash, program, pair.first.eventHashHex).isFailure) ok = false }
                        if (ok) queue.add(WidgetHostEvent(WidgetHostEventKind.ActionCommitted, actionSteps = parts.sortedBy { it.second.actionStep }.map { it.second.inputs.map { v -> v.copy(numberExpression = "") } }, myPlayer = myPlayer, eventPlayer = otherPlayer()))
                    }
                    return
                }
                val pending = pendingInputs.remove(token) ?: return
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.acknowledgeRead(ownerMainDht, element.id, sourceHash, program, pending.first.eventHashHex)
                        .onSuccess { queue.add(WidgetHostEvent(WidgetHostEventKind.Committed, inputs = pending.second.inputs.map { v -> v.copy(numberExpression = "") }, text = pending.second.text, myPlayer = myPlayer, eventPlayer = otherPlayer())) }
                        .onFailure { log("widget event acknowledgement failed: ${it.message}") }
                }
            }

            override fun reject(token: String) { if (!closed) { pendingInputs.remove(token); pendingActions.remove(token) } }

            override fun acceptInvite(token: String) {
                if (closed) return
                val pending = pendingInvites.remove(token) ?: return
                scope.launch {
                    val manager = widgetNetwork ?: return@launch
                    manager.acceptInvitation(ownerMainDht, element.id, sourceHash, program, pending.second.inviteIdHex, pending.first.sessionDht)
                        .onSuccess { acceptSignal -> waitingInviteId = pending.second.inviteIdHex; waitingOwnSessionDht = acceptSignal.sessionDht }
                        .onFailure { log("widget invitation acceptance failed: ${it.message}") }
                }
            }

            override fun declineInvite(token: String) { if (!closed) pendingInvites.remove(token) }

            override fun close() {
                closed = true
                pendingInputs.clear(); pendingActions.clear(); incomingActionPieces.clear(); pendingInvites.clear()
                outgoingByHash.clear(); outgoingActions.clear(); hashToAction.clear(); openInviteSessions.clear(); queue.clear()
                synchronized(actionLock) { actionOpen = false; actionBuffer.clear(); actionInputIds = emptySet() }
                waitingInviteId = ""; waitingOwnSessionDht = ""; activePeerSessionDht = ""; activeOwnSessionDht = ""; pairInviteId = ""; myPlayer = 0
                synchronized(randomLock) {
                    ownRandomSecret = ""; ownRandomCommit = ""; peerRandomCommit = ""; peerRandomReveal = ""
                    ownRevealPublished = false; ownCommitPublished = false; sharedRandomSeed = ""; randomFirstPlayer = 0; randomReadyEmitted = false; nextRollIndex = 1
                }
                log("widget network: runtime closed/reset instance=${instanceId.take(12)}")
            }

            override suspend fun poll(): List<WidgetHostEvent> = withContext(Dispatchers.IO) {
                if (closed) return@withContext emptyList()
                val manager = widgetNetwork ?: return@withContext emptyList()
                manager.recentSignals(instanceId).forEach { signal ->
                    val key = signalKey(signal)
                    if (key in seenSignals) return@forEach
                    if (manager.isOwnSessionDht(signal.sessionDht)) { seenSignals += key; return@forEach }
                    if (activePeerSessionDht.isNotBlank() && signal.kind in setOf(
                            WidgetNetworkEventKind.Inputs, WidgetNetworkEventKind.Text, WidgetNetworkEventKind.ReadAck,
                            WidgetNetworkEventKind.RandomCommit, WidgetNetworkEventKind.RandomReveal, WidgetNetworkEventKind.RandomRoll,
                        ) && signal.sessionDht != activePeerSessionDht) { seenSignals += key; return@forEach }
                    val event = manager.readSignalledEvent(signal) ?: return@forEach
                    if (!manager.validateRuntimeEvent(program, sourceHash, instanceId, signal, event)) { seenSignals += key; return@forEach }
                    seenSignals += key

                    when (event.kind) {
                        WidgetNetworkEventKind.Inputs -> {
                            if (event.actionIdHex.isNotBlank()) {
                                val actionKey = "${signal.sessionDht}|${event.actionIdHex}"
                                val pieces = incomingActionPieces.computeIfAbsent(actionKey) { ConcurrentHashMap() }
                                pieces[event.actionStep] = signal to event
                                if (pieces.size == event.actionCount && (1..event.actionCount).all { pieces.containsKey(it) }) {
                                    val ordered = (1..event.actionCount).mapNotNull { pieces[it] }
                                    val signatures = ordered.map { it.second.inputs.map { v -> v.inputId }.toSet() }
                                    if (signatures.distinct().size == 1) {
                                        val token = sha256Hex(ordered.joinToString("|") { it.first.eventHashHex }.toByteArray()).take(32)
                                        pendingActions[token] = ordered
                                        queue.add(WidgetHostEvent(WidgetHostEventKind.Action, token = token, actionSteps = ordered.map { it.second.inputs.map { v -> v.copy(numberExpression = "") } }, myPlayer = myPlayer, eventPlayer = otherPlayer()))
                                    }
                                    incomingActionPieces.remove(actionKey)
                                }
                            } else {
                                val token = opaqueToken(signal)
                                pendingInputs[token] = signal to event
                                queue.add(WidgetHostEvent(WidgetHostEventKind.Input, token = token, inputs = event.inputs.map { it.copy(numberExpression = "") }, text = event.text, myPlayer = myPlayer, eventPlayer = otherPlayer()))
                                if (event.text.isNotEmpty()) queue.add(WidgetHostEvent(WidgetHostEventKind.Text, text = event.text, myPlayer = myPlayer, eventPlayer = otherPlayer()))
                            }
                        }
                        WidgetNetworkEventKind.Text -> queue.add(WidgetHostEvent(WidgetHostEventKind.Text, text = event.text, myPlayer = myPlayer, eventPlayer = otherPlayer()))
                        WidgetNetworkEventKind.Invite -> {
                            val token = opaqueToken(signal); pendingInvites[token] = signal to event
                            queue.add(WidgetHostEvent(WidgetHostEventKind.Invite, token = token))
                        }
                        WidgetNetworkEventKind.Accept -> {
                            val ownInviteSession = openInviteSessions[event.inviteIdHex]
                            if (ownInviteSession != null && event.peerSessionDht == ownInviteSession) {
                                manager.acknowledgeAcceptance(ownerMainDht, element.id, sourceHash, program, event.inviteIdHex, signal.sessionDht)
                                    .onSuccess {
                                        openInviteSessions.remove(event.inviteIdHex)
                                        queue.add(WidgetHostEvent(WidgetHostEventKind.InviteAccepted, myPlayer = 1))
                                        establishPair(1, ownInviteSession, signal.sessionDht, event.inviteIdHex)
                                    }.onFailure { log("widget final invitation acknowledgement failed: ${it.message}") }
                            }
                        }
                        WidgetNetworkEventKind.AcceptAck -> {
                            if (waitingInviteId.isNotBlank() && event.inviteIdHex == waitingInviteId && event.peerSessionDht == waitingOwnSessionDht) {
                                val invite = waitingInviteId; val own = waitingOwnSessionDht
                                waitingInviteId = ""; waitingOwnSessionDht = ""
                                establishPair(2, own, signal.sessionDht, invite)
                            }
                        }
                        WidgetNetworkEventKind.ReadAck -> {
                            outgoingByHash.remove(event.acceptedEventHashHex)?.let(queue::add)
                            hashToAction.remove(event.acceptedEventHashHex)?.let { actionId ->
                                val pending = outgoingActions[actionId]
                                if (pending != null) {
                                    pending.hashes.remove(event.acceptedEventHashHex)
                                    if (pending.hashes.isEmpty()) { outgoingActions.remove(actionId); queue.add(pending.event) }
                                }
                            }
                        }
                        WidgetNetworkEventKind.RandomCommit -> {
                            if (myPlayer in 1..2) {
                                synchronized(randomLock) { peerRandomCommit = event.randomCommitHashHex }
                                maybePublishReveal()
                            }
                        }
                        WidgetNetworkEventKind.RandomReveal -> {
                            if (myPlayer in 1..2) {
                                val valid = synchronized(randomLock) { peerRandomCommit.isNotBlank() && randomCommit(event.randomRevealHex) == peerRandomCommit }
                                if (valid) {
                                    synchronized(randomLock) { peerRandomReveal = event.randomRevealHex }
                                    maybePublishReveal(); maybeFinishRandom()
                                } else log("widget random reveal rejected")
                            }
                        }
                        WidgetNetworkEventKind.RandomRoll -> {
                            val seed: String; val expected: Int; val first: Int
                            synchronized(randomLock) {
                                seed = sharedRandomSeed; first = randomFirstPlayer
                                expected = if (event.rollIndex % 2 == 1) first else if (first == 1) 2 else 1
                                if (seed.isBlank() || event.rollIndex != nextRollIndex || otherPlayer() != expected) return@forEach
                                nextRollIndex++
                            }
                            queue.add(WidgetHostEvent(WidgetHostEventKind.Roll, myPlayer = myPlayer, eventPlayer = otherPlayer(), randomFirstPlayer = first, rollIndex = event.rollIndex, rollSides = event.rollSides, rollValue = rollValue(seed, event.rollIndex, event.rollSides)))
                        }
                        WidgetNetworkEventKind.Tombstone -> Unit
                    }
                }
                buildList { while (true) { val next = queue.poll() ?: break; add(next) } }
            }
        }
    }

    suspend fun readWidgetDataDht(recordKey: String): WidgetDataSummary? = withContext(Dispatchers.IO) {
        if (recordKey.isBlank()) null else widgetNetwork?.readPublisherDataDht(recordKey)
    }

    suspend fun uploadWidgetPackage(contentType: String, bytes: ByteArray): JSONObject? =
        withContext(Dispatchers.IO) {
            val d = daemon ?: return@withContext null
            runCatching { d.uploadBlob(contentType, bytes) }
                .onFailure { log("widget package upload failed: ${it.message}") }
                .getOrNull()
        }

    /**
     * Fetches a source-only widget package after explicit viewer activation.
     * Opening a profile never calls this method on its own.
     */
    suspend fun downloadWidgetPackage(rootRecordKey: String, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val d = daemon ?: return@withContext null
            runCatching {
                val (meta, bytes) = d.downloadBlob(rootRecordKey, maxBytes)
                val expected = meta.optString("sha256_hex")
                if (expected.isNotBlank() && !sha256Hex(bytes).equals(expected, ignoreCase = true)) {
                    error("widget package blob hash mismatch")
                }
                bytes
            }.onFailure { log("widget package fetch failed: ${it.message}") }.getOrNull()
        }

    /** Uploads image bytes and returns the blob object, or null when the daemon is not ready. */
    suspend fun uploadMedia(contentType: String, bytes: ByteArray): JSONObject? =
        withContext(Dispatchers.IO) {
            val d = daemon ?: return@withContext null
            runCatching { d.uploadBlob(contentType, bytes) }
                .onFailure { log("media upload failed: ${it.message}") }
                .getOrNull()
        }

    /**
     * Fetches a media blob, verifying it before it is handed back.
     *
     * Reads the header first so an oversized blob costs one small round trip instead of a
     * download, then pulls in chunks. Chunking matters twice over: it keeps each binder
     * response well inside the transaction buffer, and it gives [keepGoing] somewhere to say
     * no — the daemon has no cancel for reads, so between chunks is the only place a fetch
     * can be abandoned.
     *
     * The SHA-256 check is the security property: blobs are content addressed, so a host that
     * serves different bytes than the profile claims is detected rather than rendered.
     */
    suspend fun downloadMedia(
        rootRecordKey: String,
        expectedSha256Hex: String,
        maxBytes: Int,
        chunkBytes: Int = 128 * 1024,
        keepGoing: () -> Boolean = { true },
    ): ByteArray? = withContext(Dispatchers.IO) {
        val d = daemon ?: return@withContext null
        runCatching {
            val header = d.readBlobRange(rootRecordKey, 0, 0, true).getJSONObject("blob")
            val total = header.getLong("total_bytes")
            if (total <= 0L || total > maxBytes.toLong()) {
                log("media rejected: ${short(rootRecordKey)} is $total bytes")
                return@runCatching null
            }
            val out = java.io.ByteArrayOutputStream(total.toInt())
            var offset = 0L
            while (offset < total) {
                if (!keepGoing()) {
                    log("media fetch abandoned at $offset/$total bytes")
                    return@runCatching null
                }
                val length = minOf(chunkBytes.toLong(), total - offset)
                val chunk = d.readBlobRange(rootRecordKey, offset, length, false)
                out.write(android.util.Base64.decode(chunk.getString("data_base64"), android.util.Base64.DEFAULT))
                offset += length
            }
            val bytes = out.toByteArray()
            val actual = sha256Hex(bytes)
            if (!actual.equals(expectedSha256Hex, ignoreCase = true)) {
                log("media hash mismatch for ${short(rootRecordKey)}; discarded")
                return@runCatching null
            }
            bytes
        }.onFailure { log("media fetch failed: ${it.message}") }.getOrNull()
    }

    fun ensureProfileAvailable(mainDht: String) = scope.launch {
        val hint = cache.get(mainDht)?.hint ?: return@launch

        // Group discovery is independent of the decorated-profile blob cache. A profile may have
        // been cached before its owner created/claimed a group, so always refresh subkey 2.
        runCatching {
            val entries = groupRuntime?.discoverUserGroups(mainDht, hint.profileRootDht).orEmpty()
            log("group directory refresh ${short(mainDht)}: ${entries.size} advertised")
        }.onFailure {
            log("group directory refresh ${short(mainDht)} failed: ${it.message}")
        }

        if (!profileDocuments.containsKey(mainDht)) {
            verifyProfile(mainDht, hint.profileRootDht)
        }
        updateSnapshots()
    }

    /** Refresh only the lightweight group directory for a known user. Safe even if their profile
     * document is already cached and therefore does not need to be downloaded again. */
    fun refreshUserGroups(mainDht: String) = scope.launch {
        val d = daemon ?: return@launch
        val root = fullRecords[mainDht]?.profileRootDht
            ?.takeIf { it.isNotBlank() }
            ?: cache.get(mainDht)?.hint?.profileRootDht?.takeIf { it.isNotBlank() }
            ?: runCatching { d.getAppRoot(mainDht).stringOrNull("root_dht") }.getOrNull()
            ?: return@launch

        runCatching {
            val entries = groupRuntime?.discoverUserGroups(mainDht, root).orEmpty()
            log("group directory refresh ${short(mainDht)}: ${entries.size} advertised")
            updateSnapshots()
        }.onFailure {
            log("group directory refresh ${short(mainDht)} failed: ${it.message}")
        }
    }

    private fun runLocalSearch(intent: DiscoveryIntent) {
        _ui.value = _ui.value.copy(searchResults = cache.search(intent, 100).filter { it.hint.mainDht != _ui.value.mainDht })
    }

    private fun ensureProfileStore() {
        val d = daemon ?: return
        val result = d.listStores()
        val stores = result.optJSONArray("stores")
        var found: JSONObject? = null
        if (stores != null) for (i in 0 until stores.length()) {
            val store = stores.getJSONObject(i)
            if (store.optString("name") == PROFILE_STORE_NAME) { found = store; break }
        }
        if (found == null) found = d.createStore(PROFILE_STORE_NAME, 4).getJSONObject("store")
        profileStoreId = found.getString("store_id")
        val root = found.getString("record_key")
        // The app root stays the profile store. Everything else, including the comment index,
        // is reached through subkeys of that record rather than by claiming the root.
        d.registerAppRoot(root)
        _ui.value = _ui.value.copy(profileRoot = root)
        log("profile store ready: $root")

        runCatching {
            val network = CommentNetwork(d).also { commentNetwork = it }
            network.ensureStores()
            network.publishIndexPointer(found.getString("store_id"))
            log("comment chains ready")
        }.onFailure { log("comment chains unavailable: ${it.message}") }
    }

    private fun readOwnRecord() {
        val d = daemon ?: return
        val storeId = profileStoreId ?: return
        runCatching {
            val result = d.readStore(storeId, listOf(PROFILE_SUBKEY), false)
            val record = decodeRecordValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return@runCatching
            if (record.mainDht != _ui.value.mainDht || record.profileRootDht != _ui.value.profileRoot) return@runCatching
            ownRecord = record
            fullRecords[record.mainDht] = record
            cache.upsert(record.toHint(nowSeconds(), VerificationState.DHT_VERIFIED), "self", nowSeconds())
            _ui.value = _ui.value.copy(
                published = true,
                discoveryName = record.name,
                discoveryDescription = record.description,
                featuresText = record.features.joinToString(", "),
            )
            runCatching { fetchVerifiedDocument(record) }.onFailure { log("own profile blob read: ${it.message}") }
            log("loaded existing profile-page record generation ${record.generation}")
        }.onFailure { log("own profile read: ${it.message}") }
    }

    private fun subscribeGroupOpenIntake() {
        val runtime = groupRuntime ?: return
        groupServiceSubscription?.let { runCatching { daemon?.unsubscribe(it) } }
        groupServiceSubscription = runtime.subscribeOpenIntake { reason ->
            log("group intake stream closed: $reason")
        }
        log("subscribed to group spectator intake")
    }

    private fun subscribeWidgetPublicNetwork() {
        val d = daemon ?: return
        widgetServiceSubscription?.let { runCatching { d.unsubscribe(it) } }
        widgetServiceSubscription = d.subscribeServiceRequests(
            serviceIdsHex = listOf(WidgetNetworkManager.PUBLIC_SERVICE_ID),
            onRequest = { raw -> scope.launch { runCatching { widgetNetwork?.receivePublicServiceRequest(raw) }.onFailure { log("widget public request rejected: ${it.message}") } } },
            onClosed = { reason -> log("widget public-network request stream closed: $reason") },
        )
        log("subscribed to public widget requests")
    }

    private fun subscribeMessages() {
        val d = daemon ?: return
        messageSubscription = d.subscribeMessages(
            onMessage = { event -> scope.launch { processIncoming(event) } },
            onClosed = { reason -> log(reason) }
        )
        log("subscribed to application messages")
    }

    private suspend fun processIncoming(event: JSONObject) {
        val payload = runCatching { Base64.decode(event.getString("payload_base64"), Base64.DEFAULT) }.getOrNull() ?: return
        val source = event.optString("sender_main_dht")

        // Direct and mailbox deliveries authenticate the sender; gossip only claims one.
        // Comment notices are accepted from the former and ignored from the latter, so a
        // stranger cannot spoof a comment into someone else's moderation queue.
        if (event.optString("delivery_kind") != "gossip") {
            when (widgetNetwork?.receiveMailboxPointer(payload, source)) {
                WidgetMailboxDisposition.Consumed, WidgetMailboxDisposition.RetryLater -> return
                WidgetMailboxDisposition.NotWidget, null -> Unit
            }
            if (GroupCustodyWireCodecV2.looksLikeCustody(payload)) {
                val custodyMessage = GroupCustodyWireCodecV2.decode(payload)
                if (custodyMessage == null) {
                    log("[groups-v2] CUSTODY_WIRE_REJECT source=${short(source)} reason=malformed")
                } else {
                    groupRuntime?.receiveCustodyWireV2(custodyMessage, source)
                }
                return
            }
            GroupEventTransportPacketV2.fromBytes(payload)?.let { packet ->
                val transport = if (event.optString("delivery_kind") == "mailbox") {
                    GroupEventTransportV2.PrivateMailbox
                } else {
                    GroupEventTransportV2.Direct
                }
                groupRuntime?.receiveCanonicalEventV2(packet, source, transport)
                return
            }
            GroupWireEnvelope.fromBytes(payload)?.let { envelope ->
                groupRuntime?.receivePrivate(envelope, source)
                return
            }
            CommentAck.fromBytes(payload)?.let { ack ->
                receiveCommentAck(ack, source)
                return
            }
            CommentNotice.fromBytes(payload)?.let { notice ->
                comments.rememberPendingNotice(source, notice)
                if (receiveCommentNotice(notice, source)) comments.forgetPendingNotice(notice.commentId)
                return
            }
            return
        }
        GroupGossipPacket.fromBytes(payload)?.let { packet ->
            gossipReceivedCount.incrementAndGet()
            receiveGroupGossip(packet, source)
            updateSnapshots()
            return
        }

        val message = runCatching { GossipMessage.decode(payload) }.getOrNull() ?: return
        gossipReceivedCount.incrementAndGet()
        when (message) {
            is GossipMessage.Summary -> {
                message.clusters.forEach { cluster ->
                    val representative = cluster.exemplars.firstOrNull() ?: MinHash(IntArray(MINHASH_SIZE) { 0xffff })
                    cluster.samples.forEach { sample -> cache.upsert(sample.approximateHint(nowSeconds(), representative), source, nowSeconds()) }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.toJson().toString().encodeToByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    .take(12)
                val nowMs = System.currentTimeMillis()
                val previousDigest = gossipSummaryDigestBySource[source]
                val previousLogAt = gossipSummaryLogAtBySource[source] ?: 0L
                if (digest != previousDigest || nowMs - previousLogAt >= GOSSIP_SUMMARY_LOG_REPEAT_MS) {
                    gossipSummaryDigestBySource[source] = digest
                    gossipSummaryLogAtBySource[source] = nowMs
                    log("gossip summary from ${short(source)}: ${message.clusters.size} clusters digest=$digest")
                }
            }
            is GossipMessage.ProfileAnnounce -> {
                message.profile.verification = VerificationState.GOSSIP_HINT
                cache.upsert(message.profile, source, nowSeconds())
                if (message.profile.profileRootDht.isNotBlank()) scope.launch { verifyProfile(message.profile.mainDht, message.profile.profileRootDht) }
            }
            is GossipMessage.SimilarityQuery -> {
                val current = nowSeconds()
                val last = queryReplyAt[source] ?: 0L
                if (source in knownPeers && current - last >= 30L) {
                    queryReplyAt[source] = current
                    val samples = cache.search(message.intent, message.limit.coerceAtMost(6)).map { it.hint }
                    val response = GossipMessage.SimilarityResponse(message.requestId, samples).toJson().toString().encodeToByteArray()
                    if (response.size <= 8 * 1024) runCatching { daemon?.sendGossip(source, response) }.onSuccess { gossipSentCount.incrementAndGet() }
                }
            }
            is GossipMessage.SimilarityResponse -> {
                message.samples.forEach { hint ->
                    hint.verification = VerificationState.GOSSIP_HINT
                    cache.upsert(hint, source, nowSeconds())
                    if (hint.profileRootDht.isNotBlank()) scope.launch { verifyProfile(hint.mainDht, hint.profileRootDht) }
                }
                log("search response ${message.requestId} from ${short(source)}: ${message.samples.size} samples")
            }
        }
        val candidates = cache.recent().map { it.hint.mainDht }.filter { it != _ui.value.mainDht }.distinct().take(64)
        if (candidates.isNotEmpty()) runCatching { daemon?.recommendNodes(candidates) }
        activeIntent?.let(::runLocalSearch)
        updateSnapshots()
    }

    private fun refreshPeers() {
        val d = daemon ?: return
        runCatching {
            val page = d.listAppPeers()
            val peers = page.optJSONArray("peers")
            knownPeers.clear()
            if (peers != null) for (i in 0 until peers.length()) {
                val peer = peers.getJSONObject(i)
                val main = peer.getString("main_dht")
                if (main == _ui.value.mainDht) continue
                knownPeers += main
                var root = peer.stringOrNull("app_root_dht")
                if (root == null) root = runCatching { d.getAppRoot(main).stringOrNull("root_dht") }.getOrNull()
                if (root != null) verifyProfile(main, root)
            }
            d.setInteractiveActivity(knownPeers.toList())
            log("app peers: cached=${page.optInt("total_cached")} returned=${knownPeers.size} search=${page.optString("search_state")}")
        }.onFailure { log("peer refresh failed: ${it.message}") }
    }

    private fun verifyProfile(expectedMainDht: String, root: String) {
        val d = daemon ?: return
        runCatching {
            val result = d.readPublicStore(root, listOf(PROFILE_SUBKEY), true)
            val record = decodeRecordValue(result.optJSONArray("values")?.optJSONObject(0)) ?: return@runCatching
            if (record.mainDht != expectedMainDht || record.profileRootDht != root) {
                log("profile rejected: app-root/profile binding mismatch for ${short(expectedMainDht)}")
                return@runCatching
            }
            require(record.profileBytes in 1..PROFILE_MAX_BYTES.toLong()) { "profile blob size outside prototype limit" }
            fetchVerifiedDocument(record)
            fullRecords[record.mainDht] = record
            cache.upsert(record.toHint(nowSeconds(), VerificationState.DHT_VERIFIED), "dht", nowSeconds())
            groupRuntime?.discoverUserGroups(record.mainDht, record.profileRootDht)
        }.onFailure { log("verify ${short(expectedMainDht)}: ${it.message}") }
    }

    private fun fetchVerifiedDocument(record: ProfilePageRecord) {
        val d = daemon ?: error("Daemon is not connected")
        val (meta, bytes) = d.downloadBlob(record.profileBlobRoot, PROFILE_MAX_BYTES)
        require(bytes.size.toLong() == record.profileBytes) { "profile blob size mismatch" }
        val expected = record.profileSha256Hex.lowercase()
        val actual = sha256Hex(bytes)
        require(actual == expected) { "profile blob SHA-256 mismatch" }
        val daemonHash = meta.optString("sha256_hex").lowercase()
        require(daemonHash.isBlank() || daemonHash == expected) { "daemon blob descriptor SHA-256 mismatch" }
        val text = bytes.decodeToString()
        val decoded = ProfileCodec.decodeText(text)
        val validation = ProfileCodec.validate(decoded)
        require(validation.ok) { "invalid VSPF profile: ${validation.message}" }
        profileDocuments[record.mainDht] = text
    }

    private fun gossipGroups() {
        val ownMainDht = _ui.value.mainDht
        val hints = groups.gossipHints(GroupGossipPacket.MAX_HINTS)
            .filterNot { hint ->
                // Preserve profile-first discovery: a locally owned Original/Claim is not announced
                // through gossip until this account's profile is published. Once another peer has
                // legitimately learned the branch, that peer may relay the verified hint.
                !_ui.value.published && hint.pointer.ownerMainDht == ownMainDht
            }
        if (hints.isEmpty() || knownPeers.isEmpty()) return
        val packet = GroupGossipPacket(System.currentTimeMillis(), hints)
        val bytes = packet.toBytes()
        if (bytes.size > 8 * 1024) {
            log("group gossip skipped: ${bytes.size} bytes exceeds gossip limit")
            return
        }
        val sent = sendGossipToSample(bytes, 6)
        val now = System.currentTimeMillis()
        // Gossip runs every few seconds, but the persisted diagnostic log should remain readable.
        // Record a heartbeat at most once per minute; actual verified changes are logged immediately.
        if (sent > 0 && now - lastGroupGossipSendLogMs >= 60_000L) {
            lastGroupGossipSendLogMs = now
            log("group gossip heartbeat: ${hints.size} branch hint(s) sent to $sent peer(s)")
        }
    }

    private fun receiveGroupGossip(packet: GroupGossipPacket, source: String) {
        if (source.isBlank() || source == _ui.value.mainDht || packet.hints.isEmpty()) return
        groupTrustV2.observePeerLive(source, "GroupGossip")
        var scheduled = 0
        packet.hints.forEach { hint ->
            val pointer = hint.pointer
            if (pointer.groupId.isBlank() || pointer.branchId.isBlank() || pointer.branchRoot.isBlank() ||
                pointer.ownerMainDht.isBlank() || pointer.creatorRoot.isBlank()) return@forEach

            val localGroup = groups.byId(pointer.groupId)
            // An unknown Original can introduce a group. A Claim is only useful once the group is
            // known, and must point back to that known creator root. This prevents arbitrary gossip
            // peers from flooding the local store with unattached claim branches.
            if (pointer.kind == GroupBranchKind.Original) {
                if (pointer.branchRoot != pointer.creatorRoot) return@forEach
            } else {
                if (localGroup == null) return@forEach
                if (localGroup.rootRecordKey.isNotBlank() && pointer.creatorRoot != localGroup.rootRecordKey) return@forEach
            }

            val localGeneration = groups.knownPulseGeneration(pointer.groupId, pointer.branchId)
            val alreadyKnown = groups.branchKnown(pointer.groupId, pointer.branchId)
            if (alreadyKnown && hint.pulseGeneration > 0L && hint.pulseGeneration <= localGeneration) {
                return@forEach
            }

            val key = "${pointer.groupId}|${pointer.branchId}"
            val stamp = maxOf(hint.pulseGeneration, hint.pulseUpdatedAt, pointer.updatedAt)
            val previousStamp = groupHintStamp[key]
            if (previousStamp != null && stamp > 0L && stamp <= previousStamp) return@forEach
            if (!groupHintInFlight.add(key)) return@forEach
            scheduled++

            // Gossip is only a hint. refreshBranch re-reads and validates the authoritative header
            // and Pulse from the DHT before anything is committed to the local group store. A failed
            // verification does not consume the hint permanently; the next gossip can retry it.
            scope.launch {
                try {
                    val loaded = runCatching { groupRuntime?.refreshBranch(pointer) }.getOrNull()
                    if (loaded != null) {
                        if (stamp > 0L) groupHintStamp[key] = stamp
                        groupTrustV2.ensureWatch(pointer.groupId, pointer.branchId)
                        val generation = loaded.second?.generation ?: -1L
                        groupTrustV2.recordEvidence(
                            peerMainDht = source,
                            kind = GroupReputationEvidenceKindV2.GossipDhtConfirmed,
                            evidenceKey = "${pointer.groupId}|${pointer.branchId}|$generation",
                            groupId = pointer.groupId,
                            detail = "gossip hint confirmed by authoritative branch DHT",
                        )
                        log("group gossip verified: ${loaded.first.name} ${pointer.kind.name} owner=${groupAuthorityName(pointer.ownerMainDht) ?: short(pointer.ownerMainDht)} pulse_generation=$generation")
                    } else {
                        log("group gossip verification unavailable: group=${short(pointer.groupId)} branch=${short(pointer.branchId)} from=${short(source)}; will retry on a later hint")
                    }
                } finally {
                    groupHintInFlight.remove(key)
                }
            }
        }
        if (scheduled > 0) {
            log("group gossip received from ${short(source)}: ${packet.hints.size} hint(s), $scheduled requiring DHT verification")
        }
    }

    private fun gossipSummary() {
        val clusters = cache.clusters(10, cache.generation() xor (nowSeconds() / 60)).map { it.copy(exemplars = it.exemplars.take(1), samples = it.samples.take(1)) }
        val message = GossipMessage.Summary(cache.generation(), nowSeconds(), clusters).toJson().toString().encodeToByteArray()
        if (message.size > 8 * 1024) { log("summary skipped: ${message.size} bytes exceeds gossip limit"); return }
        sendGossipToSample(message, 6)
    }

    private fun gossipProfileAnnounce(profile: ProfilePageRecord) {
        val message = GossipMessage.ProfileAnnounce(profile.toHint(nowSeconds(), VerificationState.GOSSIP_HINT)).toJson().toString().encodeToByteArray()
        if (message.size <= 8 * 1024) sendGossipToSample(message, 6)
    }

    private fun sendGossipToSample(bytes: ByteArray, limit: Int): Int {
        val d = daemon ?: return 0
        var sent = 0
        deterministicPeerSample(knownPeers.toList(), nowSeconds(), limit).forEach { peer ->
            runCatching { d.sendGossip(peer, bytes) }
                .onSuccess { gossipSentCount.incrementAndGet(); sent++ }
                .onFailure { log("gossip to ${short(peer)} failed: ${it.message}") }
        }
        return sent
    }

    private fun updateSnapshots() {
        val mainDht = _ui.value.mainDht
        val recent = cache.recent().map { cached -> SocialProfileRow(cached.hint, profileDocuments.containsKey(cached.hint.mainDht)) }
            .filter { it.hint.mainDht != mainDht }.take(50)
        val verified = cache.all().count { it.hint.verification == VerificationState.DHT_VERIFIED }
        val clusters = cache.clusters(10, cache.generation())
        val clusterText = buildString {
            clusters.forEach { cluster ->
                append("cluster ").append(cluster.clusterId.toString().padStart(2, '0'))
                    .append(": known=").append(cluster.knownCount)
                    .append(" exemplar=").append(cluster.exemplars.firstOrNull()?.compactHex() ?: "-")
                    .append(" rotating_samples=").append(cluster.samples.size).append('\n')
            }
        }
        if (knownPeers.isNotEmpty()) lastPeerSeenMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(recent = recent, peers = knownPeers.size, verified = verified, gossipSent = gossipSentCount.get(), gossipReceived = gossipReceivedCount.get(), clusterText = clusterText)
    }

    private fun decodeRecordValue(value: JSONObject?): ProfilePageRecord? {
        if (value == null || value.optBoolean("is_null", true)) return null
        val encoded = value.stringOrNull("value_base64") ?: return null
        return ProfilePageRecord.fromJson(JSONObject(Base64.decode(encoded, Base64.DEFAULT).decodeToString()))
    }

    private fun loadPrivateMetadata() {
        runCatching {
            var raw = vault.getText("discovery/preferences.json")
            if (raw == null && legacyPrefs.all.isNotEmpty()) {
                raw = JSONObject()
                    .put("description", legacyPrefs.getString("description", "") ?: "")
                    .put("features", legacyPrefs.getString("features", "") ?: "")
                    .put("comment_policy", legacyPrefs.getString("comment_policy", CommentPolicy.Open.name))
                    .toString()
                vault.putText("discovery/preferences.json", raw)
            }
            if (raw != null && legacyPrefs.all.isNotEmpty()) {
                check(legacyPrefs.edit().clear().commit()) { "could not remove legacy discovery plaintext" }
            }
            val root = JSONObject(raw ?: "{}")
            _ui.value = _ui.value.copy(
                discoveryDescription = root.optString("description", ""),
                featuresText = root.optString("features", ""),
                commentPolicy = CommentPolicy.fromWire(root.optString("comment_policy", null)),
                avoidance = root.optDouble("avoidance", .75).toFloat(),
                commonPenalty = root.optDouble("common_penalty", .20).toFloat(),
                stuffingPenalty = root.optDouble("stuffing_penalty", .25).toFloat(),
                novelty = root.optDouble("novelty", .10).toFloat(),
            )
        }.onFailure { log("private discovery preferences could not be loaded: ${it.message}") }
    }

    private fun persistMetadata() {
        if (!vault.attached) return
        val state = _ui.value
        runCatching {
            vault.putText(
                "discovery/preferences.json",
                JSONObject()
                    .put("description", state.discoveryDescription)
                    .put("features", state.featuresText)
                    .put("comment_policy", state.commentPolicy.name)
                    .put("avoidance", state.avoidance.toDouble())
                    .put("common_penalty", state.commonPenalty.toDouble())
                    .put("stuffing_penalty", state.stuffingPenalty.toDouble())
                    .put("novelty", state.novelty.toDouble())
                    .toString()
            )
        }.onFailure { log("private discovery preferences could not be saved: ${it.message}") }
    }

    private companion object Health {
        /** How long with no peers before the status says something is wrong rather than "looking". */
        const val STALE_PEERS_AFTER_SECONDS = 300L
    }

    // ---------------------------------------------------------------------
    // Comments
    // ---------------------------------------------------------------------

    fun setCommentPolicy(policy: CommentPolicy) {
        _ui.value = _ui.value.copy(commentPolicy = policy)
        persistMetadata()
    }

    /** The rule a page's owner published, or Open when their record has not been read yet. */
    fun commentPolicyFor(ownerMainDht: String): CommentPolicy =
        if (ownerMainDht == _ui.value.mainDht) ownRecord?.commentPolicy ?: _ui.value.commentPolicy
        else fullRecords[ownerMainDht]?.commentPolicy ?: CommentPolicy.Open

    private fun bumpComments() {
        _ui.value = _ui.value.copy(commentRevision = _ui.value.commentRevision + 1)
    }

    /**
     * Writes a comment to this device's own chain and tells the page owner about it.
     *
     * The comment is stored locally straight away so the UI shows it immediately. What state
     * it lands in depends on who owns the page: your own page accepts your own words, and
     * anyone else's starts unaccepted until they keep it.
     */
    fun postComment(pageKey: String, body: String, openMode: Boolean, replyTo: String? = null) = scope.launch {
        val network = commentNetwork
        val me = _ui.value.mainDht
        if (network == null || me.isBlank()) {
            log("comment not posted: comment chains are not ready")
            return@launch
        }
        val owner = ownerKeyOf(pageKey)
        val policy = commentPolicyFor(owner)
        if (policy == CommentPolicy.Closed) {
            log("comment not posted: this profile has comments turned off")
            return@launch
        }
        val wire = WireComment(
            id = makeId("comment"),
            pageKey = pageKey,
            authorKey = me,
            authorName = _ui.value.discoveryName.ifBlank { "Someone" },
            body = body.trim().take(MAX_COMMENT_CHARS),
            createdAt = System.currentTimeMillis(),
            replyTo = replyTo,
        )

        // Store it before touching the network. Writing to a DHT record takes seconds, and
        // waiting for that made a posted comment look like it had simply disappeared.
        comments.upsert(wire.toComment(CommentState.Sending, CommentOrigin.Direct))
        bumpComments()

        runCatching {
            val result = network.post(wire, owner, notify = true)
            val pointer = result.pointer
            comments.rememberPointer(wire.id, pointer.recordKey, pointer.subkey)

            val state = when {
                owner == me -> CommentState.Accepted
                result.notified -> CommentState.Provisional
                else -> CommentState.Failed
            }
            comments.upsert(
                wire.toComment(
                    state = state,
                    origin = CommentOrigin.Direct,
                )
            )
            if (owner == me) {
                network.keep(IndexEntry(pageKey, pointer, me, wire.id, commentDigest(wire)))
            }
            if (result.notified) {
                log("comment stored and owner notified; awaiting confirmation")
            } else {
                log("comment stored but owner notification failed: ${result.notificationError ?: "unknown error"}")
            }
        }.onFailure {
            comments.upsert(wire.toComment(CommentState.Failed, CommentOrigin.Direct))
            log("comment post failed: ${it.message}")
        }
        bumpComments()
    }

    /** Publishes a pointer to a comment in this device's index, making it visible to readers. */
    fun keepComment(id: String) = scope.launch {
        val network = commentNetwork ?: return@launch
        val comment = comments.byId(id) ?: return@launch
        val pointer = comments.pointerFor(id)
        if (pointer == null) {
            log("cannot keep ${short(id)}: no pointer recorded for it")
            return@launch
        }
        runCatching {
            val ptr = CommentChain.Pointer(pointer.first, pointer.second)
            val wire = WireComment(
                id = comment.id,
                pageKey = comment.pageKey,
                authorKey = comment.authorKey,
                authorName = comment.authorName,
                body = comment.body,
                createdAt = comment.createdAt,
                replyTo = comment.replyTo,
            )
            network.keep(IndexEntry(comment.pageKey, ptr, comment.authorKey, id, commentDigest(wire)))
            comments.setState(id, CommentState.Accepted)
            if (comment.authorKey.isNotBlank() && comment.authorKey != _ui.value.mainDht) {
                runCatching { network.acknowledge(comment.authorKey, id, CommentAck.Status.Published) }
            }
            bumpComments()
            log("comment kept on ${comment.pageKey.substringAfter('#')}")
        }.onFailure { log("keeping the comment failed: ${it.message}") }
    }

    /** Pulls the comments a page owner has kept and merges them into the local store. */
    fun syncComments(ownerMainDht: String, pageKey: String) = scope.launch {
        val network = commentNetwork ?: return@launch
        val root = fullRecords[ownerMainDht]?.profileRootDht?.takeIf { it.isNotBlank() } ?: return@launch
        runCatching {
            val fetched = network.fetchKept(root, pageKey)
            fetched.forEach { wire ->
                comments.upsert(wire.toComment(CommentState.Accepted, CommentOrigin.Direct))
            }
            if (fetched.isNotEmpty()) {
                bumpComments()
                log("synced ${fetched.size} comment(s) for ${short(ownerMainDht)}")
            }
        }.onFailure { log("comment sync failed: ${it.message}") }
    }

    /**
     * Reads messages that arrived while this app was not subscribed.
     *
     * The live [subscribeMessages] stream only carries what turns up while the app is open,
     * so without this a comment posted while the phone was in a pocket is delivered to the
     * mailbox and never seen.
     *
     * [force] asks the daemon to check the mailbox immediately rather than on its own
     * schedule; used once at startup, since that is when the backlog is largest.
     */
    private suspend fun drainInbox(force: Boolean) {
        val d = daemon ?: return
        runCatching {
            if (force) runCatching { d.triggerMessageRetrieval() }
            val listed = d.listInbox().optJSONArray("messages") ?: return@runCatching
            var handled = 0
            var skipped = 0
            for (i in 0 until listed.length()) {
                if (handled >= INBOX_DRAIN_LIMIT) break
                val summary = listed.optJSONObject(i) ?: continue
                val id = summary.optString("message_id_hex")
                if (id.isBlank()) continue

                val message = runCatching { d.readInbox(id).optJSONObject("message") }.getOrNull() ?: continue
                val payload = runCatching {
                    Base64.decode(message.optString("payload_base64"), Base64.DEFAULT)
                }.getOrNull() ?: continue
                val sender = message.optString("sender_main_dht")

                when (widgetNetwork?.receiveMailboxPointer(payload, sender)) {
                    WidgetMailboxDisposition.Consumed -> {
                        runCatching { d.deleteInbox(id) }
                        handled++
                        continue
                    }
                    WidgetMailboxDisposition.RetryLater -> {
                        // The signed pointer is valid, but the sender's Session DHT may not have
                        // propagated yet. Keep this mailbox item and retry on the next sweep.
                        skipped++
                        continue
                    }
                    WidgetMailboxDisposition.NotWidget, null -> Unit
                }

                if (GroupCustodyWireCodecV2.looksLikeCustody(payload)) {
                    val custodyMessage = GroupCustodyWireCodecV2.decode(payload)
                    if (custodyMessage == null) {
                        log("[groups-v2] CUSTODY_WIRE_REJECT source=${short(sender)} reason=malformed-mailbox")
                    } else {
                        groupRuntime?.receiveCustodyWireV2(custodyMessage, sender)
                    }
                    runCatching { d.deleteInbox(id) }
                    handled++
                    continue
                }

                val groupV2Packet = GroupEventTransportPacketV2.fromBytes(payload)
                if (groupV2Packet != null) {
                    groupRuntime?.receiveCanonicalEventV2(
                        groupV2Packet,
                        sender,
                        GroupEventTransportV2.PrivateMailbox,
                    )
                    runCatching { d.deleteInbox(id) }
                    handled++
                    continue
                }

                val groupEnvelope = GroupWireEnvelope.fromBytes(payload)
                if (groupEnvelope != null) {
                    groupRuntime?.receivePrivate(groupEnvelope, sender)
                    runCatching { d.deleteInbox(id) }
                    handled++
                    continue
                }

                val ack = CommentAck.fromBytes(payload)
                if (ack != null) {
                    receiveCommentAck(ack, sender)
                    runCatching { d.deleteInbox(id) }
                    handled++
                    continue
                }
                val notice = CommentNotice.fromBytes(payload)
                if (notice == null) {
                    // Not ours. The inbox belongs to the identity rather than to this app, so
                    // deleting something we cannot parse could destroy another app's mail.
                    skipped++
                    continue
                }
                comments.rememberPendingNotice(sender, notice)
                val consumed = receiveCommentNotice(notice, sender)
                if (consumed) {
                    comments.forgetPendingNotice(notice.commentId)
                    runCatching { d.deleteInbox(id) }
                    handled++
                } else {
                    // Keep it in the mailbox. A pointer notice can arrive before the DHT value
                    // has propagated; the next sweep will try again instead of losing it.
                    skipped++
                }
            }
            retryPendingCommentNotices()
            if (handled > 0 || skipped > 0) {
                log("inbox: handled $handled Weave message(s), left $skipped message(s) or deferred notices")
            }
        }.onFailure { log("inbox drain failed: ${it.message}") }
    }

    /**
     * Handles a notice that someone commented on one of this device's pages. The notice only
     * carries a pointer, so the body is fetched from the commenter's own record.
     */
    private suspend fun retryPendingCommentNotices() {
        val queued = comments.pendingNotices().take(20)
        for (pending in queued) {
            if (receiveCommentNotice(pending.notice, pending.sender)) {
                comments.forgetPendingNotice(pending.notice.commentId)
            }
        }
    }

    private suspend fun receiveCommentNotice(notice: CommentNotice, sender: String): Boolean {
        val network = commentNetwork ?: return false
        val me = _ui.value.mainDht
        if (sender.isBlank() || ownerKeyOf(notice.pageKey) != me) {
            log("ignored a comment notice for someone else's page")
            return true
        }
        if (notice.commentId.isBlank() || notice.pointer.recordKey.isBlank() ||
            notice.pointer.subkey !in 1 until CommentChain.CHAIN_SUBKEYS) {
            log("ignored malformed comment notice from ${short(sender)}")
            return true
        }

        // Message delivery can outrun DHT propagation. Retry for a short period before leaving
        // a mailbox notice queued for the next sweep.
        var wire: WireComment? = null
        val retryDelays = longArrayOf(0L, 2_000L, 5_000L)
        for (pause in retryDelays) {
            if (pause > 0) delay(pause)
            wire = network.fetchOne(notice.pointer)
            if (wire != null) break
        }
        val resolved = wire
        if (resolved == null) {
            log("comment notice from ${short(sender)} is valid but its DHT body is not readable yet; keeping it for retry")
            return false
        }
        if (resolved.id != notice.commentId) {
            log("comment notice id does not match the stored comment; ignored")
            return true
        }
        if (resolved.authorKey.isNotBlank() && resolved.authorKey != sender) {
            log("comment notice from ${short(sender)} claims a different author; ignored")
            return true
        }
        if (resolved.pageKey != notice.pageKey) {
            log("comment notice page does not match the stored comment; ignored")
            return true
        }

        val policy = ownRecord?.commentPolicy ?: _ui.value.commentPolicy
        if (policy == CommentPolicy.Closed) {
            runCatching { network.acknowledge(sender, resolved.id, CommentAck.Status.Rejected) }
            log("comment from ${short(sender)} refused: comments are turned off")
            return true
        }

        comments.rememberPointer(resolved.id, notice.pointer.recordKey, notice.pointer.subkey)
        if (policy == CommentPolicy.Moderated) {
            comments.upsert(resolved.toComment(CommentState.Held, CommentOrigin.Direct))
            runCatching { network.acknowledge(sender, resolved.id, CommentAck.Status.WaitingApproval) }
            bumpComments()
            log("comment received from ${short(sender)} (waiting for approval)")
            return true
        }

        // Open really means open: no hidden heuristic moderation. Publish the exact pointer.
        return runCatching {
            network.keep(IndexEntry(resolved.pageKey, notice.pointer, resolved.authorKey, resolved.id, commentDigest(resolved)))
            comments.upsert(resolved.toComment(CommentState.Accepted, CommentOrigin.Direct))
            runCatching { network.acknowledge(sender, resolved.id, CommentAck.Status.Published) }
            bumpComments()
            log("comment received from ${short(sender)} and published")
            true
        }.getOrElse { error ->
            // Keep the notice so a later inbox sweep can retry publication rather than silently
            // claiming an open comment was accepted when it never reached the public index.
            comments.upsert(resolved.toComment(CommentState.Held, CommentOrigin.Direct))
            bumpComments()
            log("comment arrived but could not be published yet: ${error.message}")
            false
        }
    }

    private fun receiveCommentAck(ack: CommentAck, sender: String) {
        val comment = comments.byId(ack.commentId) ?: return
        if (ownerKeyOf(comment.pageKey) != sender) {
            log("ignored comment acknowledgement from unexpected profile ${short(sender)}")
            return
        }
        val state = when (ack.status) {
            CommentAck.Status.Published -> CommentState.Accepted
            CommentAck.Status.WaitingApproval -> CommentState.Held
            CommentAck.Status.Rejected -> CommentState.Dropped
            else -> return
        }
        comments.setState(comment.id, state)
        bumpComments()
        log("comment ${short(comment.id)} acknowledged by ${short(sender)}: ${ack.status.name}")
    }

    fun dropComment(id: String) = scope.launch {
        val network = commentNetwork
        val comment = comments.byId(id) ?: return@launch
        comments.setState(id, CommentState.Dropped)
        if (network != null && comment.authorKey.isNotBlank() && comment.authorKey != _ui.value.mainDht) {
            runCatching { network.acknowledge(comment.authorKey, id, CommentAck.Status.Rejected) }
        }
        bumpComments()
        log("comment dropped on ${comment.pageKey.substringAfter('#')}")
    }

    fun retryComment(id: String) = scope.launch {
        val network = commentNetwork ?: return@launch
        val comment = comments.byId(id) ?: return@launch
        val pointer = comments.pointerFor(id) ?: return@launch
        val owner = ownerKeyOf(comment.pageKey)
        runCatching {
            network.notifyOwner(owner, comment.pageKey, CommentChain.Pointer(pointer.first, pointer.second), comment.id)
            comments.setState(id, CommentState.Provisional)
            bumpComments()
            log("comment notification retried; awaiting confirmation")
        }.onFailure {
            comments.setState(id, CommentState.Failed)
            bumpComments()
            log("comment retry failed: ${it.message}")
        }
    }

    private fun updateStatus(status: String) { _ui.value = _ui.value.copy(status = status); log(status) }

    /**
     * Sets the status without writing a log line, for the once-per-second health text.
     * Skips the write entirely when nothing changed so the flow does not churn.
     */
    private fun setStatusQuiet(status: String) {
        if (_ui.value.status == status) return
        _ui.value = _ui.value.copy(status = status)
    }

    /**
     * One line describing what the network is actually doing.
     *
     * Peers going to zero and staying there is what an offline node looks like from here —
     * the daemon keeps answering, so "connected" alone is misleading.
     */
    fun diagnosticBreadcrumb(category: String, message: String) {
        WeaveDiagnostics.event(appContext, category, message)
    }

    private fun healthLine(): String {
        val state = _ui.value
        val since = if (lastPeerSeenMs == 0L) 0L else (System.currentTimeMillis() - lastPeerSeenMs) / 1000
        return when {
            state.peers > 0 -> "Connected \u00B7 ${state.peers} peer${if (state.peers == 1) "" else "s"}"
            lastPeerSeenMs == 0L -> "Connected \u00B7 looking for peers"
            since >= STALE_PEERS_AFTER_SECONDS ->
                "No peers for ${since / 60} min \u00B7 the daemon may have lost its network connection"
            else -> "Connected \u00B7 looking for peers"
        }
    }

    /** Everything the app knows, for the Copy Log button. */
    fun diagnosticReport(): String {
        val state = _ui.value
        return buildString {
            appendLine("Weave diagnostic report")
            appendLine("generated: ${formatLogTime(System.currentTimeMillis())}")
            appendLine("app id: ${DaemonClient.APP_ID}")
            appendLine("connected: ${state.connected}")
            appendLine("main dht: ${state.mainDht.ifBlank { "(none)" }}")
            appendLine("profile store root: ${state.profileRoot.ifBlank { "(none)" }}")
            appendLine("published: ${state.published}")
            appendLine("status: ${state.status}")
            appendLine("peers: ${state.peers}  verified: ${state.verified}  hints: ${state.recent.size}")
            appendLine("last peer seen: ${if (lastPeerSeenMs == 0L) "never" else "${(System.currentTimeMillis() - lastPeerSeenMs) / 1000}s ago"}")
            appendLine("publishing: ${state.publishing}")
            appendLine()
            appendLine("--- group responsibilities for this account ---")
            groups.responsibilityLines(state.mainDht).forEach { appendLine(it) }
            appendLine()
            appendLine("--- Groups v2 Event Store ---")
            groupEventsV2.diagnosticLines { groupId -> groups.byId(groupId)?.name }.forEach { appendLine(it) }
            appendLine()
            appendLine("--- Groups v2 Custody + Branch Decisions ---")
            groupCustodyV2.diagnosticLines { groupId -> groups.byId(groupId)?.name }.forEach { appendLine(it) }
            appendLine()
            appendLine("--- Groups v2 Reputation + Authority Continuity ---")
            groupTrustV2.diagnosticLines().forEach { appendLine(it) }
            groupRuntime?.abuseDiagnosticLinesV2()?.forEach { appendLine(it) }
            appendLine()
            appendLine("--- lifecycle / daemon RPC breadcrumbs ---")
            val breadcrumbs = WeaveDiagnostics.tail(appContext)
            appendLine(if (breadcrumbs.isNotBlank()) breadcrumbs else "(none)")
            appendLine("--- network/group log ---")
            val persisted = readPersistentLogTail(DIAGNOSTIC_LOG_KEEP_BYTES)
            append(if (persisted.isNotBlank()) persisted else state.debugLog)
        }
    }

    /** Wall-clock of the last snapshot that saw at least one peer. Zero means never. */
    private var lastPeerSeenMs = 0L

    private fun logGroupResponsibilities(reason: String) {
        val main = _ui.value.mainDht
        log("group responsibilities snapshot ($reason):")
        groups.responsibilityLines(main).forEach { line -> log("groups: responsibility: $line") }
    }

    private fun formatLogTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(LOG_TIMESTAMP_FORMAT)

    private fun readPersistentLogTail(maxChars: Int = 180_000): String = runCatching {
        if (!diagnosticLogFile.exists()) return@runCatching ""
        diagnosticLogFile.readText().takeLast(maxChars.coerceAtLeast(1_000))
    }.getOrDefault("")

    private fun appendPersistentLog(line: String) {
        runCatching {
            diagnosticLogFile.parentFile?.mkdirs()
            diagnosticLogFile.appendText(line + "\n")
            if (diagnosticLogFile.length() > DIAGNOSTIC_LOG_MAX_BYTES) {
                val tail = diagnosticLogFile.readText().takeLast(DIAGNOSTIC_LOG_KEEP_BYTES)
                diagnosticLogFile.writeText(tail.substringAfter('\n', tail))
            }
        }
    }

    @Synchronized
    private fun log(message: String) {
        val now = System.currentTimeMillis()
        val line = "[${formatLogTime(now)}] $message"
        appendPersistentLog(line)

        // The persisted file is the authoritative diagnostic log.  Mirroring a growing
        // ~180 KiB String into SocialUiState on every single network/gossip line forced the root
        // Compose tree to recompose for logs it does not even display, and created severe GC churn
        // on busy daemon startups.  Refresh the UI-facing tail at most once per second instead.
        if (now - lastDebugUiRefreshMs >= 1_000L) {
            lastDebugUiRefreshMs = now
            val tail = readPersistentLogTail(180_000)
            _ui.value = _ui.value.copy(debugLog = tail)
        }
    }

}

private fun deterministicPeerSample(peers: List<String>, seed: Long, limit: Int): List<String> =
    peers.sortedBy { peer ->
        var h = seed xor 0x1e3779b97f4a7c15L
        peer.encodeToByteArray().forEach { byte -> h = java.lang.Long.rotateLeft(h, 5) xor (byte.toLong() and 0xff); h *= 0x100000001b3L }
        h
    }.take(limit)

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
internal fun short(value: String): String = if (value.length <= 18) value else value.take(9) + "…" + value.takeLast(6)
private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
