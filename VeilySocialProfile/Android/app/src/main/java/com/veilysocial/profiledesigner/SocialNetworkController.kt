package com.veilysocial.profiledesigner

import android.content.Context
import android.util.Base64
import com.veilysocial.profiledesigner.backbone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

private const val PROFILE_STORE_NAME = "veilysocial-profile-page-v1"
private const val PROFILE_SUBKEY = 0

/** How often to sweep the mailbox for notices that arrived while the app was closed. */
private const val INBOX_DRAIN_MS = 60_000L

/** Messages handled per sweep, so a large backlog cannot stall the worker loop. */
private const val INBOX_DRAIN_LIMIT = 50
private const val PEER_REFRESH_MS = 20_000L
private const val GOSSIP_INTERVAL_MS = 12_000L
private const val PROFILE_MAX_BYTES = 4 * 1024 * 1024

data class SocialProfileRow(val hint: ProfileHint, val openable: Boolean)

data class SocialUiState(
    val status: String = "Starting…",
    val mainDht: String = "",
    val profileRoot: String = "",
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("veilysocial_profile_discovery", Context.MODE_PRIVATE)

    /** Local read model for comments. Owned here so the sync path and the UI share one instance. */
    val comments: CommentStore = LocalCommentStore(appContext)
    private var commentNetwork: CommentNetwork? = null
    private val _ui = MutableStateFlow(
        SocialUiState(
            discoveryDescription = prefs.getString("description", "") ?: "",
            commentPolicy = CommentPolicy.fromWire(prefs.getString("comment_policy", null)),
            featuresText = prefs.getString("features", "") ?: "",
        )
    )
    val ui: StateFlow<SocialUiState> = _ui.asStateFlow()

    private var daemon: DaemonClient? = null
    private var messageSubscription: Long? = null
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

    fun start() {
        // Checking isActive rather than nullness is what makes retry work: when the worker
        // throws, the job completes but the reference stays non-null, so the old guard made
        // every subsequent start() a silent no-op.
        if (networkJob?.isActive == true) return
        runCatching { daemon?.close() }
        daemon = DaemonClient(appContext)
        networkJob = scope.launch {
            try {
                updateStatus("Connecting to VeilKnit daemon…")
                daemon!!.connect { updateStatus(it) }
                val identity = daemon!!.identity()
                val mainDht = identity.getString("main_dht")
                _ui.value = _ui.value.copy(mainDht = mainDht, status = "Connected; preparing profile-page DHT…")
                ensureProfileStore()
                readOwnRecord()
                subscribeMessages()
                refreshPeers()
                updateSnapshots()
                // Startup is done. Without this the status stayed on "preparing profile-page
                // DHT" forever, on healthy and broken nodes alike, which made a genuine
                // outage indistinguishable from normal operation.
                setStatusQuiet(healthLine())

                drainInbox(force = true)

                var lastPeerRefresh = 0L
                var lastGossip = 0L
                var lastInboxDrain = System.currentTimeMillis()
                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastPeerRefresh >= PEER_REFRESH_MS) {
                        refreshPeers(); lastPeerRefresh = nowMs
                    }
                    if (nowMs - lastInboxDrain >= INBOX_DRAIN_MS) {
                        drainInbox(force = false); lastInboxDrain = nowMs
                    }
                    if (nowMs - lastGossip >= GOSSIP_INTERVAL_MS) {
                        gossipSummary(); lastGossip = nowMs
                    }
                    activeIntent?.let(::runLocalSearch)
                    updateSnapshots()
                    setStatusQuiet(healthLine())
                    delay(1000)
                }
            } catch (t: Throwable) {
                log("network worker stopped: ${t.message}")
                updateStatus("Error: ${t.message}")
            }
        }
    }

    /** Tears the connection down and dials again. Used by the retry control on the gate. */
    fun restart() {
        messageSubscription?.let { runCatching { daemon?.unsubscribe(it) } }
        messageSubscription = null
        networkJob?.cancel()
        networkJob = null
        runCatching { daemon?.close() }
        daemon = null
        _ui.value = _ui.value.copy(status = "Reconnecting to VeilKnit daemon…")
        start()
    }

    fun stop() {
        messageSubscription?.let { daemon?.unsubscribe(it) }
        messageSubscription = null
        networkJob?.cancel()
        networkJob = null
        daemon?.close()
        daemon = null
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
    fun setAvoidance(value: Float) { _ui.value = _ui.value.copy(avoidance = value) }
    fun setCommonPenalty(value: Float) { _ui.value = _ui.value.copy(commonPenalty = value) }
    fun setStuffingPenalty(value: Float) { _ui.value = _ui.value.copy(stuffingPenalty = value) }
    fun setNovelty(value: Float) { _ui.value = _ui.value.copy(novelty = value) }

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
            val blob = d.uploadBlob("application/x-veilysocial-vspf-text;version=3", bytes)
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
                    vspfVersion = 3,
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

    fun refreshNow() = scope.launch { refreshPeers(); updateSnapshots() }
    fun gossipNow() = scope.launch { gossipSummary(); ownRecord?.let(::gossipProfileAnnounce); updateSnapshots() }

    fun search() = scope.launch {
        val state = _ui.value
        val positive = state.positiveMainDht.takeIf { it.isNotBlank() }?.let { cache.get(it)?.hint }
        val negative = state.negativeMainDht.takeIf { it.isNotBlank() }?.let { cache.get(it)?.hint }
        val intent = DiscoveryIntent(
            positive = positive?.let { listOf(WeightedSignature(it.minhash, 1f)) } ?: emptyList(),
            negative = negative?.let { listOf(WeightedSignature(it.minhash, 1f)) } ?: emptyList(),
            nameQuery = state.nameQuery.trim().takeIf { it.isNotEmpty() },
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

    // ---------------------------------------------------------------------
    // Media blobs
    //
    // Images live in their own blobs rather than inside the profile payload, so a page with
    // five pictures is five independent fetches that can be skipped, deferred or abandoned
    // instead of one payload that must arrive whole.
    // ---------------------------------------------------------------------

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
        if (profileDocuments.containsKey(mainDht)) return@launch
        verifyProfile(mainDht, hint.profileRootDht)
        updateSnapshots()
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
                discoveryName = record.name,
                discoveryDescription = record.description,
                featuresText = record.features.joinToString(", "),
            )
            runCatching { fetchVerifiedDocument(record) }.onFailure { log("own profile blob read: ${it.message}") }
            log("loaded existing profile-page record generation ${record.generation}")
        }.onFailure { log("own profile read: ${it.message}") }
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
            CommentNotice.fromBytes(payload)?.let { notice -> receiveCommentNotice(notice, source) }
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
                log("gossip summary from ${short(source)}: ${message.clusters.size} clusters")
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

    private fun sendGossipToSample(bytes: ByteArray, limit: Int) {
        val d = daemon ?: return
        deterministicPeerSample(knownPeers.toList(), nowSeconds(), limit).forEach { peer ->
            runCatching { d.sendGossip(peer, bytes) }.onSuccess { gossipSentCount.incrementAndGet() }.onFailure { log("gossip to ${short(peer)} failed: ${it.message}") }
        }
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

    private fun persistMetadata() {
        val state = _ui.value
        prefs.edit()
            .putString("description", state.discoveryDescription)
            .putString("features", state.featuresText)
            .putString("comment_policy", state.commentPolicy.name)
            .apply()
    }

    private companion object Health {
        /** How long with no peers before the status says something is wrong rather than "looking". */
        const val STALE_PEERS_AFTER_SECONDS = 300L
    }

    // ---------------------------------------------------------------------
    // Comments
    // ---------------------------------------------------------------------

    fun setCommentPolicy(policy: CommentPolicy) {
        prefs.edit().putString("comment_policy", policy.name).apply()
        _ui.value = _ui.value.copy(commentPolicy = policy)
    }

    /** The rule a page's owner published, or Open when their record has not been read yet. */
    fun commentPolicyFor(ownerMainDht: String): CommentPolicy =
        if (ownerMainDht == _ui.value.mainDht) _ui.value.commentPolicy
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
    fun postComment(pageKey: String, body: String, openMode: Boolean) = scope.launch {
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
        )

        // Store it before touching the network. Writing to a DHT record takes seconds, and
        // waiting for that made a posted comment look like it had simply disappeared.
        comments.upsert(wire.toComment(CommentState.Sending, CommentOrigin.Direct))
        bumpComments()

        val settled = when {
            owner == me -> CommentState.Accepted
            policy == CommentPolicy.Moderated -> CommentState.Held
            else -> triageComment(wire.body, me, owner, comments.allForPage(pageKey), openMode)
        }

        runCatching {
            val pointer = network.post(wire, owner, notify = true)
            comments.rememberPointer(wire.id, pointer.recordKey, pointer.subkey)
            comments.upsert(
                wire.toComment(
                    state = settled,
                    origin = if (openMode) CommentOrigin.Open else CommentOrigin.Direct,
                    expiresAt = if (openMode && settled == CommentState.Provisional) {
                        System.currentTimeMillis() + OPEN_COMMENT_TTL_MS
                    } else 0L,
                )
            )
            if (owner == me && settled == CommentState.Accepted) {
                network.keep(IndexEntry(pageKey, pointer, me, wire.id))
            }
            log("comment posted to ${short(owner)} (${settled.name.lowercase()})")
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
            network.keep(
                IndexEntry(comment.pageKey, CommentChain.Pointer(pointer.first, pointer.second), comment.authorKey, id)
            )
            comments.setState(id, CommentState.Accepted)
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
    private fun drainInbox(force: Boolean) {
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

                val notice = CommentNotice.fromBytes(payload)
                if (notice == null) {
                    // Not ours. The inbox belongs to the identity rather than to this app, so
                    // deleting something we cannot parse could destroy another app's mail.
                    skipped++
                    continue
                }
                receiveCommentNotice(notice, sender)
                runCatching { d.deleteInbox(id) }
                handled++
            }
            if (handled > 0 || skipped > 0) {
                log("inbox: handled $handled comment notice(s), left $skipped message(s) for other apps")
            }
        }.onFailure { log("inbox drain failed: ${it.message}") }
    }

    /**
     * Handles a notice that someone commented on one of this device's pages. The notice only
     * carries a pointer, so the body is fetched from the commenter's own record.
     */
    private fun receiveCommentNotice(notice: CommentNotice, sender: String) {
        val network = commentNetwork ?: return
        val me = _ui.value.mainDht
        if (ownerKeyOf(notice.pageKey) != me) {
            log("ignored a comment notice for someone else's page")
            return
        }
        val wire = network.fetchOne(notice.pointer)
        if (wire == null) {
            log("comment notice from ${short(sender)} pointed at nothing readable")
            return
        }
        // The pointer is in the sender's own record, so the sender must be the author.
        if (wire.authorKey.isNotBlank() && wire.authorKey != sender) {
            log("comment notice from ${short(sender)} claims a different author; ignored")
            return
        }
        if (wire.pageKey != notice.pageKey) {
            log("comment notice page does not match the stored comment; ignored")
            return
        }
        val policy = _ui.value.commentPolicy
        if (policy == CommentPolicy.Closed) {
            log("comment from ${short(sender)} refused: comments are turned off")
            return
        }
        comments.rememberPointer(wire.id, notice.pointer.recordKey, notice.pointer.subkey)
        val state = if (policy == CommentPolicy.Moderated) {
            CommentState.Held
        } else {
            triageComment(wire.body, wire.authorKey, me, comments.allForPage(wire.pageKey), openMode = false)
        }
        comments.upsert(wire.toComment(state, CommentOrigin.Direct))
        bumpComments()

        // Open mode has to publish the pointer, not just mark it accepted locally. Without
        // this the comment was visible only to the page owner: everyone else reads the
        // published index, and the index never learned about it.
        if (state == CommentState.Accepted) {
            runCatching {
                network.keep(IndexEntry(wire.pageKey, notice.pointer, wire.authorKey, wire.id))
            }.onFailure { log("could not publish the kept comment: ${it.message}") }
        }
        log("comment received from ${short(sender)} (${state.name.lowercase()})")
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
            appendLine("VeilySocial diagnostic report")
            appendLine("generated: ${nowSeconds()}")
            appendLine("app id: ${DaemonClient.APP_ID}")
            appendLine("main dht: ${state.mainDht.ifBlank { "(none)" }}")
            appendLine("profile root: ${state.profileRoot.ifBlank { "(not published)" }}")
            appendLine("status: ${state.status}")
            appendLine("peers: ${state.peers}  verified: ${state.verified}  hints: ${state.recent.size}")
            appendLine("last peer seen: ${if (lastPeerSeenMs == 0L) "never" else "${(System.currentTimeMillis() - lastPeerSeenMs) / 1000}s ago"}")
            appendLine("publishing: ${state.publishing}")
            appendLine()
            appendLine("--- log ---")
            append(state.debugLog)
        }
    }
    /** Wall-clock of the last snapshot that saw at least one peer. Zero means never. */
    private var lastPeerSeenMs = 0L

    private fun log(message: String) {
        val line = "[${nowSeconds()}] $message"
        val old = _ui.value.debugLog
        val merged = if (old.length > 45_000) old.takeLast(35_000) + "\n" + line else if (old.isBlank()) line else "$old\n$line"
        _ui.value = _ui.value.copy(debugLog = merged)
    }
}

private fun deterministicPeerSample(peers: List<String>, seed: Long, limit: Int): List<String> =
    peers.sortedBy { peer ->
        var h = seed xor 0x1e3779b97f4a7c15L
        peer.encodeToByteArray().forEach { byte -> h = java.lang.Long.rotateLeft(h, 5) xor (byte.toLong() and 0xff); h *= 0x100000001b3L }
        h
    }.take(limit)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
internal fun short(value: String): String = if (value.length <= 18) value else value.take(9) + "…" + value.takeLast(6)
private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
