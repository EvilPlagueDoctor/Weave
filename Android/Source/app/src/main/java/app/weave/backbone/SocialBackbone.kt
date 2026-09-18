package app.weave.backbone

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.ArrayDeque
import kotlin.math.max

const val SOCIAL_PROTOCOL_VERSION = 1
const val MINHASH_SIZE = 64
const val RECENT_LIMIT = 50
const val CACHE_LIMIT = 2000

enum class VerificationState { GOSSIP_HINT, MULTI_SOURCE_HINT, APP_ROOT_CONFIRMED, DHT_VERIFIED }

/**
 * What a profile allows in its comment section. Published in the profile record so other
 * people's clients know the rule before they write anything, rather than discovering it when
 * their comment is silently dropped.
 */
enum class CommentPolicy {
    /** Comments appear as soon as they are posted. */
    Open,

    /** Comments wait for the profile owner to keep them. */
    Moderated,

    /** No comments accepted. Clients hide the composer entirely. */
    Closed;

    companion object {
        fun fromWire(value: String?): CommentPolicy =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Open
    }
}

data class ProfilePageRecord(
    val schemaVersion: Int = 1,
    val mainDht: String,
    val profileRootDht: String,
    val generation: Long,
    val updatedAt: Long,
    val name: String,
    val description: String,
    val features: List<String>,
    val profileBlobId: String,
    val profileBlobRoot: String,
    val profileSha256Hex: String,
    val profileBytes: Long,
    val vspfVersion: Int = 4,
    val commentPolicy: CommentPolicy = CommentPolicy.Open,
) {
    fun signature() = MinHash.fromFeatures(extractFeatures(description, features))
    fun toHint(observedAt: Long, verification: VerificationState) = ProfileHint(
        mainDht, profileRootDht, generation, updatedAt, observedAt, name,
        description.take(180), features.take(16), signature(), verification
    )
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion).put("main_dht", mainDht)
        .put("profile_root_dht", profileRootDht).put("generation", generation)
        .put("updated_at", updatedAt).put("name", name).put("description", description)
        .put("features", JSONArray(features)).put("profile_blob_id", profileBlobId)
        .put("profile_blob_root", profileBlobRoot).put("profile_sha256_hex", profileSha256Hex)
        .put("profile_bytes", profileBytes).put("vspf_version", vspfVersion)
        .put("comment_policy", commentPolicy.name)

    companion object {
        fun fromJson(obj: JSONObject) = ProfilePageRecord(
            schemaVersion = obj.optInt("schema_version", 1),
            mainDht = obj.getString("main_dht"), profileRootDht = obj.getString("profile_root_dht"),
            generation = obj.optLong("generation", 1), updatedAt = obj.optLong("updated_at", 0),
            name = obj.optString("name"), description = obj.optString("description"),
            features = obj.optJSONArray("features")?.stringList() ?: emptyList(),
            profileBlobId = obj.optString("profile_blob_id"), profileBlobRoot = obj.getString("profile_blob_root"),
            profileSha256Hex = obj.getString("profile_sha256_hex"), profileBytes = obj.optLong("profile_bytes"),
            vspfVersion = obj.optInt("vspf_version", 3),
            // Absent on records written before the field existed, which read as Open - the
            // behaviour those profiles already had.
            commentPolicy = CommentPolicy.fromWire(obj.optString("comment_policy")),
        )
    }
}

data class MinHash(val values: IntArray) {
    fun similarity(other: MinHash): Float {
        val count = minOf(values.size, other.values.size)
        if (count == 0) return 0f
        var same = 0
        for (i in 0 until count) if (values[i] == other.values[i]) same++
        return same.toFloat() / count
    }
    fun compactHex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            digest.update((value and 0xff).toByte())
            digest.update(((value ushr 8) and 0xff).toByte())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun toWireString(): String {
        val bytes = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xff).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        fun fromFeatures(features: List<String>): MinHash {
            if (features.isEmpty()) return MinHash(IntArray(MINHASH_SIZE) { 0xffff })
            val minima = LongArray(MINHASH_SIZE) { -1L }
            features.forEach { feature ->
                val base = stableHash64(feature.encodeToByteArray())
                for (index in minima.indices) {
                    val seed = splitMix64((index.toULong() * 0x9E3779B97F4A7C15uL).toLong())
                    val candidate = splitMix64(base xor seed)
                    if (java.lang.Long.compareUnsigned(candidate, minima[index]) < 0) minima[index] = candidate
                }
            }
            return MinHash(IntArray(MINHASH_SIZE) { index -> (minima[index] and 0xffffL).toInt() })
        }
        fun fromWireString(encoded: String): MinHash {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size == MINHASH_SIZE * 2) { "Expected ${MINHASH_SIZE * 2} MinHash bytes, got ${bytes.size}" }
            return MinHash(IntArray(MINHASH_SIZE) { index ->
                (bytes[index * 2].toInt() and 0xff) or ((bytes[index * 2 + 1].toInt() and 0xff) shl 8)
            })
        }
    }
}

data class ProfileHint(
    val mainDht: String,
    val profileRootDht: String,
    val generation: Long,
    val updatedAt: Long,
    var observedAt: Long,
    val name: String,
    val description: String,
    val features: List<String>,
    val minhash: MinHash,
    var verification: VerificationState,
) {
    fun normalizedFeatures() = extractFeatures(description, features)
    fun toJson(): JSONObject = JSONObject()
        .put("main_dht", mainDht).put("profile_root_dht", profileRootDht)
        .put("generation", generation).put("updated_at", updatedAt).put("observed_at", observedAt)
        .put("name", name).put("description", description).put("features", JSONArray(features))
        .put("minhash", JSONObject().put("values", minhash.toWireString()))
        .put("verification", verification.name.lowercase())

    companion object {
        fun fromJson(obj: JSONObject) = ProfileHint(
            mainDht = obj.getString("main_dht"), profileRootDht = obj.getString("profile_root_dht"),
            generation = obj.getLong("generation"), updatedAt = obj.optLong("updated_at"), observedAt = obj.optLong("observed_at"),
            name = obj.optString("name"), description = obj.optString("description"),
            features = obj.optJSONArray("features")?.stringList() ?: emptyList(),
            minhash = MinHash.fromWireString(obj.getJSONObject("minhash").getString("values")),
            verification = when (obj.optString("verification")) {
                "dht_verified" -> VerificationState.DHT_VERIFIED
                "app_root_confirmed" -> VerificationState.APP_ROOT_CONFIRMED
                "multi_source_hint" -> VerificationState.MULTI_SOURCE_HINT
                else -> VerificationState.GOSSIP_HINT
            }
        )
    }
}

data class ProfileSampleRef(
    val mainDht: String,
    val profileRootDht: String,
    val generation: Long,
    val updatedAt: Long,
    val name: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("main_dht", mainDht).put("profile_root_dht", profileRootDht)
        .put("generation", generation).put("updated_at", updatedAt).put("name", name)

    fun approximateHint(observedAt: Long, clusterSignature: MinHash) = ProfileHint(
        mainDht, profileRootDht, generation, updatedAt, observedAt, name, "", emptyList(),
        clusterSignature, VerificationState.GOSSIP_HINT
    )

    companion object {
        fun fromHint(hint: ProfileHint) = ProfileSampleRef(hint.mainDht, hint.profileRootDht, hint.generation, hint.updatedAt, hint.name)
        fun fromJson(obj: JSONObject) = ProfileSampleRef(
            obj.getString("main_dht"), obj.getString("profile_root_dht"), obj.optLong("generation"),
            obj.optLong("updated_at"), obj.optString("name")
        )
    }
}

data class WeightedSignature(val signature: MinHash, val weight: Float = 1f)
data class DiscoveryIntent(
    val positive: List<WeightedSignature> = emptyList(),
    val negative: List<WeightedSignature> = emptyList(),
    val nameQuery: String? = null,
    val positiveTerms: List<String> = emptyList(),
    val negativeTerms: List<String> = emptyList(),
    val avoidanceStrength: Float = .75f,
    val commonTermPenalty: Float = .20f,
    val stuffingPenalty: Float = .25f,
    val noveltyWeight: Float = .10f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("positive", JSONArray(positive.map { JSONObject().put("signature", JSONObject().put("values", it.signature.toWireString())).put("weight", it.weight) }))
        .put("negative", JSONArray(negative.map { JSONObject().put("signature", JSONObject().put("values", it.signature.toWireString())).put("weight", it.weight) }))
        .apply { if (!nameQuery.isNullOrBlank()) put("name_query", nameQuery) }
        .put("positive_terms", JSONArray(positiveTerms)).put("negative_terms", JSONArray(negativeTerms))
        .put("avoidance_strength", avoidanceStrength).put("common_term_penalty", commonTermPenalty)
        .put("stuffing_penalty", stuffingPenalty).put("novelty_weight", noveltyWeight)

    companion object {
        fun fromJson(obj: JSONObject): DiscoveryIntent = DiscoveryIntent(
            positive = obj.optJSONArray("positive")?.weightedList() ?: emptyList(),
            negative = obj.optJSONArray("negative")?.weightedList() ?: emptyList(),
            nameQuery = obj.optString("name_query").takeIf { it.isNotBlank() },
            positiveTerms = obj.optJSONArray("positive_terms")?.stringList() ?: emptyList(),
            negativeTerms = obj.optJSONArray("negative_terms")?.stringList() ?: emptyList(),
            avoidanceStrength = obj.optDouble("avoidance_strength", .75).toFloat(),
            commonTermPenalty = obj.optDouble("common_term_penalty", .20).toFloat(),
            stuffingPenalty = obj.optDouble("stuffing_penalty", .25).toFloat(),
            noveltyWeight = obj.optDouble("novelty_weight", .10).toFloat(),
        )
    }
}

data class ScoredProfile(
    val hint: ProfileHint,
    var score: Float,
    val positiveSimilarity: Float,
    val negativeSimilarity: Float,
    val nameScore: Float,
    val termScore: Float,
    val commonPenalty: Float,
    val stuffingPenalty: Float,
    var noveltyBonus: Float = 0f,
)

data class ClusterDigest(val clusterId: Int, val exemplars: List<MinHash>, val knownCount: Int, val samples: List<ProfileSampleRef>) {
    fun toJson(): JSONObject = JSONObject().put("cluster_id", clusterId)
        .put("exemplars", JSONArray(exemplars.map { JSONObject().put("values", it.toWireString()) }))
        .put("known_count", knownCount).put("samples", JSONArray(samples.map { it.toJson() }))
    companion object {
        fun fromJson(obj: JSONObject) = ClusterDigest(
            obj.getInt("cluster_id"),
            obj.getJSONArray("exemplars").let { array -> List(array.length()) { i -> MinHash.fromWireString(array.getJSONObject(i).getString("values")) } },
            obj.optInt("known_count"),
            obj.getJSONArray("samples").let { array -> List(array.length()) { i -> ProfileSampleRef.fromJson(array.getJSONObject(i)) } }
        )
    }
}

sealed class GossipMessage {
    abstract fun toJson(): JSONObject
    data class Summary(val generation: Long, val createdAt: Long, val clusters: List<ClusterDigest>) : GossipMessage() {
        override fun toJson() = JSONObject().put("kind", "summary").put("protocol_version", SOCIAL_PROTOCOL_VERSION)
            .put("generation", generation).put("created_at", createdAt).put("clusters", JSONArray(clusters.map { it.toJson() }))
    }
    data class ProfileAnnounce(val profile: ProfileHint) : GossipMessage() {
        override fun toJson() = JSONObject().put("kind", "profile_announce").put("protocol_version", SOCIAL_PROTOCOL_VERSION).put("profile", profile.toJson())
    }
    data class SimilarityQuery(val requestId: Long, val intent: DiscoveryIntent, val limit: Int) : GossipMessage() {
        override fun toJson() = JSONObject().put("kind", "similarity_query").put("protocol_version", SOCIAL_PROTOCOL_VERSION)
            .put("request_id", requestId).put("intent", intent.toJson()).put("limit", limit)
    }
    data class SimilarityResponse(val requestId: Long, val samples: List<ProfileHint>) : GossipMessage() {
        override fun toJson() = JSONObject().put("kind", "similarity_response").put("protocol_version", SOCIAL_PROTOCOL_VERSION)
            .put("request_id", requestId).put("samples", JSONArray(samples.map { it.toJson() }))
    }
    companion object {
        fun decode(bytes: ByteArray): GossipMessage? {
            val obj = JSONObject(bytes.decodeToString())
            if (obj.optInt("protocol_version") != SOCIAL_PROTOCOL_VERSION) return null
            return when (obj.optString("kind")) {
                "summary" -> Summary(obj.optLong("generation"), obj.optLong("created_at"), obj.getJSONArray("clusters").let { a -> List(a.length()) { ClusterDigest.fromJson(a.getJSONObject(it)) } })
                "profile_announce" -> ProfileAnnounce(ProfileHint.fromJson(obj.getJSONObject("profile")))
                "similarity_query" -> SimilarityQuery(obj.getLong("request_id"), DiscoveryIntent.fromJson(obj.getJSONObject("intent")), obj.optInt("limit", 20))
                "similarity_response" -> SimilarityResponse(obj.getLong("request_id"), obj.getJSONArray("samples").let { a -> List(a.length()) { ProfileHint.fromJson(a.getJSONObject(it)) } })
                else -> null
            }
        }
    }
}

data class CachedProfile(var hint: ProfileHint, val firstSeenAt: Long, var lastSeenAt: Long, var sourceCount: Int)

/**
 * Shared discovery state.
 *
 * Every method is synchronized on the instance. The controller runs its worker loop, each
 * incoming message, each profile verification and each comment operation as separate
 * coroutines on Dispatchers.IO, which is a thread pool, so reads and writes genuinely do
 * overlap. Only [upsert] was synchronized before, which left readers iterating the recent
 * deque while a writer mutated it - a ConcurrentModificationException on whichever thread
 * happened to be reading.
 */
class KnowledgeCache {
    private val profiles = LinkedHashMap<String, CachedProfile>()
    private val recent = ArrayDeque<String>()
    private var generation: Long = 0

    @Synchronized fun generation() = generation
    @Synchronized fun size() = profiles.size
    @Synchronized fun all() = profiles.values.toList()
    @Synchronized fun get(mainDht: String) = profiles[mainDht]
    @Synchronized fun remove(mainDht: String) { if (profiles.remove(mainDht) != null) { recent.remove(mainDht); generation++ } }

    /** Drop observations that belonged to a previous VeilKnit daemon account/profile. */
    @Synchronized fun clear() {
        profiles.clear()
        recent.clear()
        generation++
    }

    @Synchronized fun upsert(hint: ProfileHint, source: String?, now: Long): Boolean {
        hint.observedAt = max(hint.observedAt, now)
        val current = profiles[hint.mainDht]
        var changed = false
        if (current == null) {
            profiles[hint.mainDht] = CachedProfile(hint, now, now, if (source == null) 0 else 1)
            changed = true
        } else {
            current.lastSeenAt = now
            if (source != null) current.sourceCount = minOf(255, current.sourceCount + 1)
            if (hint.generation > current.hint.generation || rank(hint.verification) > rank(current.hint.verification) || hint.updatedAt > current.hint.updatedAt) {
                current.hint = hint
                changed = true
            }
            if (current.sourceCount >= 2 && current.hint.verification == VerificationState.GOSSIP_HINT) current.hint.verification = VerificationState.MULTI_SOURCE_HINT
        }
        recent.remove(hint.mainDht); recent.addFirst(hint.mainDht)
        while (recent.size > RECENT_LIMIT) recent.removeLast()
        if (profiles.size > CACHE_LIMIT) profiles.entries.minByOrNull { it.value.lastSeenAt }?.key?.let { profiles.remove(it); recent.remove(it) }
        if (changed) generation++
        return changed
    }

    @Synchronized fun recent(): List<CachedProfile> = recent.mapNotNull { profiles[it] }.take(RECENT_LIMIT)

    @Synchronized fun search(intent: DiscoveryIntent, limit: Int = 100): List<ScoredProfile> {
        val stats = corpusStats()
        val positiveTerms = intent.positiveTerms.flatMap(::tokenize)
        val negativeTerms = intent.negativeTerms.flatMap(::tokenize)
        val base = profiles.values.map { cached ->
            val hint = cached.hint
            val features = hint.normalizedFeatures().toSet()
            val positive = intent.positive.maxOfOrNull { hint.minhash.similarity(it.signature) * max(0f, it.weight) } ?: 0f
            val negative = intent.negative.maxOfOrNull { hint.minhash.similarity(it.signature) * max(0f, it.weight) } ?: 0f
            val nameScore = intent.nameQuery?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { query ->
                val name = hint.name.lowercase()
                when {
                    name == query -> 1.50f
                    name.startsWith(query) -> 1.00f
                    name.contains(query) -> .55f
                    else -> 0f
                }
            } ?: 0f
            var terms = 0f
            val common = mutableListOf<Float>()
            positiveTerms.forEach { term -> if (term in features) { val c = stats[term]?.toFloat()?.div(max(1, profiles.size)) ?: 0f; terms += .2f + .8f * (1f - c); common += c } }
            negativeTerms.forEach { if (it in features) terms -= 1f }
            if (positiveTerms.isNotEmpty()) terms /= positiveTerms.size
            val commonPenalty = (common.average().takeIf { !it.isNaN() } ?: 0.0).toFloat() * intent.commonTermPenalty
            val stuffing = stuffingSignal(hint.description, hint.features) * intent.stuffingPenalty
            ScoredProfile(hint, positive + nameScore + terms - negative * intent.avoidanceStrength - commonPenalty - stuffing, positive, negative, nameScore, terms, commonPenalty, stuffing)
        }.sortedByDescending { it.score }.toMutableList()

        if (intent.noveltyWeight <= 0f) return base.take(limit)
        val selected = mutableListOf<ScoredProfile>()
        while (base.isNotEmpty() && selected.size < limit) {
            val best = base.maxByOrNull { candidate ->
                val seen = selected.maxOfOrNull { candidate.hint.minhash.similarity(it.hint.minhash) } ?: 0f
                candidate.score + (1f - seen) * intent.noveltyWeight
            } ?: break
            base.remove(best)
            val seen = selected.maxOfOrNull { best.hint.minhash.similarity(it.hint.minhash) } ?: 0f
            best.noveltyBonus = (1f - seen) * intent.noveltyWeight
            best.score += best.noveltyBonus
            selected += best
        }
        return selected
    }

    @Synchronized fun clusters(desired: Int = 10, seed: Long = generation): List<ClusterDigest> {
        val items = profiles.values.map { it.hint }
        if (items.isEmpty()) return emptyList()
        val k = desired.coerceIn(1, 32).coerceAtMost(items.size)
        val medoids = mutableListOf(seedIndex(items, seed))
        while (medoids.size < k) {
            val candidate = items.indices.filterNot { it in medoids }.maxByOrNull { index ->
                1f - medoids.maxOf { items[index].minhash.similarity(items[it].minhash) }
            } ?: break
            medoids += candidate
        }
        val groups = MutableList(medoids.size) { mutableListOf<Int>() }
        items.indices.forEach { index ->
            val group = medoids.indices.maxByOrNull { g -> items[index].minhash.similarity(items[medoids[g]].minhash) } ?: 0
            groups[group] += index
        }
        return groups.mapIndexedNotNull { clusterId, members ->
            if (members.isEmpty()) return@mapIndexedNotNull null
            val medoid = members.take(48).maxByOrNull { candidate -> members.take(96).sumOf { other -> items[candidate].minhash.similarity(items[other].minhash).toDouble() } } ?: members[0]
            val exemplars = listOf(medoid) + members.sortedBy { items[it].minhash.similarity(items[medoid].minhash) }.filter { it != medoid }.take(2)
            val samples = members.sortedBy { splitMix64(seed xor stableHash64(items[it].mainDht.encodeToByteArray())) }.take(8).map { ProfileSampleRef.fromHint(items[it]) }
            ClusterDigest(clusterId, exemplars.map { items[it].minhash }, members.size, samples)
        }
    }

    @Synchronized fun librarianReport(terms: List<String>, gossipSent: Long, gossipReceived: Long): String {
        val stats = corpusStats()
        val total = profiles.size
        val unique = stats.size
        return buildString {
            appendLine("Weave librarian data")
            appendLine("query: ${terms.joinToString(" ").ifBlank { "(empty)" }}")
            appendLine("cached profiles: $total")
            appendLine("known terms: $unique")
            appendLine("gossip sent: $gossipSent")
            appendLine("gossip received: $gossipReceived")
            if (terms.isEmpty()) {
                appendLine()
                appendLine("Enter one or more words to see per-word counts.")
            } else {
                terms.forEach { term ->
                    val count = stats[term] ?: 0
                    val prevalence = if (total == 0) 0.0 else count.toDouble() / total.toDouble() * 100.0
                    appendLine()
                    appendLine("--- $term ---")
                    appendLine("profiles containing term: $count")
                    appendLine("local prevalence: ${"%.2f".format(java.util.Locale.US, prevalence)}%")
                }
            }
            appendLine()
            append("Note: this report is Weave's local discovery cache. This uploaded client does not expose the daemon lexical-library statistics API.")
        }
    }

    private fun corpusStats(): Map<String, Int> {
        val stats = HashMap<String, Int>()
        profiles.values.forEach { cached -> cached.hint.normalizedFeatures().toSet().forEach { stats[it] = (stats[it] ?: 0) + 1 } }
        return stats
    }
}

fun extractFeatures(description: String, declared: List<String>): List<String> {
    val set = linkedSetOf<String>()
    declared.take(64).flatMap(::tokenize).filter { it.length >= 2 }.forEach { set += it }
    tokenize(description).filter { it.length >= 3 && it !in STOP_WORDS }.forEach { if (set.size < 64) set += it }
    return set.take(64)
}

fun tokenize(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+"))
    .map { it.trim('-', '_') }.filter { it.isNotBlank() }

fun stuffingSignal(description: String, declared: List<String>): Float {
    val raw = declared.flatMap(::tokenize) + tokenize(description)
    if (raw.isEmpty()) return 0f
    val unique = raw.toSet().size
    val repeat = 1f - unique.toFloat() / raw.size
    val breadth = ((unique - 28).coerceAtLeast(0) / 72f).coerceIn(0f, 1f)
    return (repeat * .65f + breadth * .35f).coerceIn(0f, 1f)
}

private fun rank(state: VerificationState) = when (state) {
    VerificationState.GOSSIP_HINT -> 0; VerificationState.MULTI_SOURCE_HINT -> 1
    VerificationState.APP_ROOT_CONFIRMED -> 2; VerificationState.DHT_VERIFIED -> 3
}

private fun seedIndex(items: List<ProfileHint>, seed: Long): Int = items.indices.minByOrNull { splitMix64(seed xor stableHash64(items[it].mainDht.encodeToByteArray())) } ?: 0

private fun stableHash64(bytes: ByteArray): Long {
    var hash = 0xcbf29ce484222325uL
    bytes.forEach { byte -> hash = hash xor byte.toUByte().toULong(); hash *= 0x100000001b3uL }
    return splitMix64(hash.toLong())
}

private fun splitMix64(value: Long): Long {
    var x = value.toULong() + 0x9E3779B97F4A7C15uL
    x = (x xor (x shr 30)) * 0xBF58476D1CE4E5B9uL
    x = (x xor (x shr 27)) * 0x94D049BB133111EBuL
    return (x xor (x shr 31)).toLong()
}

private fun JSONArray.stringList(): List<String> = List(length()) { getString(it) }
private fun JSONArray.weightedList(): List<WeightedSignature> = List(length()) { index ->
    val obj = getJSONObject(index)
    WeightedSignature(MinHash.fromWireString(obj.getJSONObject("signature").getString("values")), obj.optDouble("weight", 1.0).toFloat())
}

private val STOP_WORDS = setOf("the", "and", "for", "that", "with", "this", "from", "have", "your", "you", "are", "was", "were", "but", "not", "all", "can", "about", "into", "our", "out", "too", "use", "using", "some", "more", "very", "just", "like", "what", "when", "where", "who", "why", "how")
