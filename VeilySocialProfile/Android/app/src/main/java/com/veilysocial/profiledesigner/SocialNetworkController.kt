package com.veilysocial.profiledesigner

import android.content.Context
import android.util.Base64
import com.veilysocial.profiledesigner.backbone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest

private const val PROFILE_STORE_NAME = "veilysocial-profile-page-v1"
private const val PROFILE_SUBKEY = 0
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
)

class SocialNetworkController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("veilysocial_profile_discovery", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        SocialUiState(
            discoveryDescription = prefs.getString("description", "") ?: "",
            featuresText = prefs.getString("features", "") ?: "",
        )
    )
    val ui: StateFlow<SocialUiState> = _ui.asStateFlow()

    private var daemon: DaemonClient? = null
    private var messageSubscription: Long? = null
    private var networkJob: Job? = null
    private val cache = KnowledgeCache()
    private val fullRecords = LinkedHashMap<String, ProfilePageRecord>()
    private val profileDocuments = LinkedHashMap<String, String>()
    private val knownPeers = LinkedHashSet<String>()
    private val queryReplyAt = HashMap<String, Long>()
    private var profileStoreId: String? = null
    private var ownRecord: ProfilePageRecord? = null
    private var activeIntent: DiscoveryIntent? = null
    private var nextRequestId = 1L
    private var gossipSent = 0L
    private var gossipReceived = 0L

    fun start() {
        if (networkJob != null) return
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

                var lastPeerRefresh = 0L
                var lastGossip = 0L
                while (isActive) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastPeerRefresh >= PEER_REFRESH_MS) {
                        refreshPeers(); lastPeerRefresh = nowMs
                    }
                    if (nowMs - lastGossip >= GOSSIP_INTERVAL_MS) {
                        gossipSummary(); lastGossip = nowMs
                    }
                    activeIntent?.let(::runLocalSearch)
                    updateSnapshots()
                    delay(1000)
                }
            } catch (t: Throwable) {
                log("network worker stopped: ${t.message}")
                updateStatus("Error: ${t.message}")
            }
        }
    }

    fun stop() {
        messageSubscription?.let { daemon?.unsubscribe(it) }
        messageSubscription = null
        networkJob?.cancel()
        networkJob = null
        daemon?.close()
        daemon = null
        scope.cancel()
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
        d.registerAppRoot(root)
        _ui.value = _ui.value.copy(profileRoot = root)
        log("profile store ready: $root")
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
        if (event.optString("delivery_kind") != "gossip") return
        val payload = runCatching { Base64.decode(event.getString("payload_base64"), Base64.DEFAULT) }.getOrNull() ?: return
        val source = event.optString("sender_main_dht")
        val message = runCatching { GossipMessage.decode(payload) }.getOrNull() ?: return
        gossipReceived++
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
                    if (response.size <= 8 * 1024) runCatching { daemon?.sendGossip(source, response) }.onSuccess { gossipSent++ }
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
            runCatching { d.sendGossip(peer, bytes) }.onSuccess { gossipSent++ }.onFailure { log("gossip to ${short(peer)} failed: ${it.message}") }
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
        _ui.value = _ui.value.copy(recent = recent, peers = knownPeers.size, verified = verified, gossipSent = gossipSent, gossipReceived = gossipReceived, clusterText = clusterText)
    }

    private fun decodeRecordValue(value: JSONObject?): ProfilePageRecord? {
        if (value == null || value.optBoolean("is_null", true)) return null
        val encoded = value.stringOrNull("value_base64") ?: return null
        return ProfilePageRecord.fromJson(JSONObject(Base64.decode(encoded, Base64.DEFAULT).decodeToString()))
    }

    private fun persistMetadata() {
        val state = _ui.value
        prefs.edit().putString("description", state.discoveryDescription).putString("features", state.featuresText).apply()
    }

    private fun updateStatus(status: String) { _ui.value = _ui.value.copy(status = status); log(status) }
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
private fun short(value: String): String = if (value.length <= 18) value else value.take(9) + "…" + value.takeLast(6)
private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
