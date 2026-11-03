package app.weave

import app.weave.backbone.ProfileHint
import app.weave.backbone.ProfilePageRecord
import app.weave.backbone.VerificationState
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Last-known-good, account-scoped discovery snapshot. The daemon encrypts the named blob and
 * atomically swaps its index; no plaintext profile copies or media bytes are written to disk.
 * This is display data, never authorization or proof of current availability.
 */
internal class EncryptedDiscoverySnapshot(private val vault: PrivateVault) {
    data class Entry(val hint: ProfileHint, val record: ProfilePageRecord?, val document: String?)
    data class Restored(val entries: List<Entry>, val verifiedAt: Map<String, Long>, val directoriesAt: Map<String, Long>)

    fun read(owner: String): Restored? {
        val bytes = vault.getNamedBlob(NAMESPACE, NAME, MAX_COMPRESSED_BYTES) ?: return null
        require(bytes.size <= MAX_COMPRESSED_BYTES) { "discovery snapshot too large" }
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                require(out.size() + n <= MAX_UNCOMPRESSED_BYTES) { "discovery snapshot expands beyond limit" }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }
        val root = JSONObject(raw.decodeToString())
        if (root.optInt("v") != 1 || root.optString("owner") != owner) return null
        val arr = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (i in 0 until minOf(arr.length(), MAX_HINTS)) {
                val obj = arr.optJSONObject(i) ?: continue
                val item = runCatching {
                    val hint = ProfileHint.fromJson(obj.getJSONObject("hint"))
                    if (hint.mainDht == owner || hint.mainDht.isBlank()) return@runCatching null
                    val record = obj.optJSONObject("record")?.let(ProfilePageRecord::fromJson)
                        ?.takeIf { it.mainDht == hint.mainDht && it.profileRootDht == hint.profileRootDht }
                    val document = obj.optString("document").takeIf {
                        it.isNotEmpty() && it.length <= MAX_DOCUMENT_CHARS && record != null
                    }?.takeIf { text ->
                        val bytes = text.encodeToByteArray()
                        bytes.size.toLong() == record!!.profileBytes &&
                            sha256(bytes).equals(record.profileSha256Hex, ignoreCase = true) &&
                            runCatching { ProfileCodec.validate(ProfileCodec.decodeText(text)).ok }.getOrDefault(false)
                    }
                    Entry(hint, record, document)
                }.getOrNull()
                if (item != null) add(item)
            }
        }
        fun timestamps(key: String): Map<String, Long> {
            val obj = root.optJSONObject(key) ?: return emptyMap()
            return buildMap {
                val iter = obj.keys()
                while (iter.hasNext()) {
                    val id = iter.next()
                    val timestamp = obj.optLong(id)
                    if (timestamp > 0 && entries.any { it.hint.mainDht == id }) put(id, timestamp)
                }
            }
        }
        return Restored(entries, timestamps("verified_at"), timestamps("directories_at"))
    }

    fun write(owner: String, entries: List<Entry>, checkedAt: Map<String, Long>, directoriesAt: Map<String, Long>): Pair<Int, Int> {
        val selected = entries.asSequence().filter { it.hint.mainDht.isNotBlank() && it.hint.mainDht != owner }
            .distinctBy { it.hint.mainDht }.take(MAX_HINTS).toList()
        var documents = 0
        var documentChars = 0
        val arr = JSONArray()
        selected.forEach { entry ->
            val obj = JSONObject().put("hint", entry.hint.toJson())
            entry.record?.let { obj.put("record", it.toJson()) }
            val doc = entry.document
            if (entry.record != null && doc != null && documents < MAX_DOCUMENTS &&
                doc.length <= MAX_DOCUMENT_CHARS && documentChars + doc.length <= MAX_TOTAL_DOCUMENT_CHARS) {
                obj.put("document", doc)
                documents++
                documentChars += doc.length
            }
            arr.put(obj)
        }
        fun times(map: Map<String, Long>) = JSONObject().also { obj ->
            selected.forEach { item -> map[item.hint.mainDht]?.takeIf { it > 0L }?.let { obj.put(item.hint.mainDht, it) } }
        }
        val payload = JSONObject().put("v", 1).put("owner", owner).put("entries", arr)
            .put("verified_at", times(checkedAt)).put("directories_at", times(directoriesAt))
            .toString().encodeToByteArray()
        require(payload.size <= MAX_UNCOMPRESSED_BYTES) { "discovery snapshot too large" }
        val zipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(payload) }
        }.toByteArray()
        require(zipped.size <= MAX_COMPRESSED_BYTES) { "compressed discovery snapshot too large" }
        vault.putNamedBlob(NAMESPACE, NAME, "application/x-weave-discovery-snapshot+gzip", zipped, PrivateVault.Retention.Persistent)
        return selected.size to documents
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val NAMESPACE = "discovery/cache-v1"
        private const val NAME = "last-good"
        private const val MAX_HINTS = 280
        private const val MAX_DOCUMENTS = 48
        private const val MAX_DOCUMENT_CHARS = 180_000
        private const val MAX_TOTAL_DOCUMENT_CHARS = 2_500_000
        private const val MAX_UNCOMPRESSED_BYTES = 4 * 1024 * 1024
        private const val MAX_COMPRESSED_BYTES = 3 * 1024 * 1024
    }
}
