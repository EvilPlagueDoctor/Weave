package app.weave

import android.content.Context
import java.io.File
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Synchronous facade over the daemon-owned encrypted app vault.
 *
 * Calls are deliberately usable by the existing repositories, which already run persistence on
 * app/background threads. The daemon is the authority for account + APP_ID scoping; this class
 * never accepts either namespace from callers.
 */
class PrivateVault private constructor(private val context: Context) {
    interface Participant {
        fun onVaultAttached() {}
        fun onVaultDetached() {}
    }

    enum class Retention(val wire: String) {
        Persistent("persistent"), Cache("cache"), Temporary("temporary"), DeleteOnShutdown("delete_on_shutdown")
    }

    @Volatile private var daemon: DaemonClient? = null
    @Volatile private var profileId: String? = null
    @Volatile private var epoch: Long = 0L
    private val participants = CopyOnWriteArraySet<Participant>()

    val attached: Boolean get() = daemon != null && profileId != null

    @Synchronized
    fun attach(client: DaemonClient, activeProfileId: String) {
        val changed = profileId != activeProfileId || daemon !== client
        if (changed && attached) detach()
        daemon = client
        profileId = activeProfileId
        epoch++
        participants.forEach { runCatching { it.onVaultAttached() } }
    }

    @Synchronized
    fun detach() {
        val hadSession = daemon != null || profileId != null
        daemon = null
        profileId = null
        if (hadSession) epoch++
        participants.forEach { runCatching { it.onVaultDetached() } }
    }

    fun register(participant: Participant) {
        participants += participant
        if (attached) runCatching { participant.onVaultAttached() }
    }

    fun unregister(participant: Participant) { participants -= participant }

    fun generation(): Long = epoch

    /**
     * Runs a multi-step app operation only if it still belongs to the same vault attachment that
     * started it. Holding this monitor also prevents account detach/attach halfway through the
     * operation, which is important for asynchronous downloads finishing during a profile switch.
     */
    @Synchronized
    fun <T> withGeneration(expected: Long, block: () -> T): T? {
        if (!attached || epoch != expected) return null
        return block()
    }

    private fun api(): DaemonClient = daemon ?: error("VeilKnit private vault is locked")

    fun putValue(key: String, bytes: ByteArray, retention: Retention = Retention.Persistent, ttlSeconds: Long? = null) {
        api().putPrivateValue(key, bytes, retention.wire, ttlSeconds)
    }

    fun getValue(key: String): ByteArray? = api().getPrivateValue(key)
    fun deleteValue(key: String): Boolean = api().deletePrivateValue(key)
    fun listValueKeys(): List<String> = api().listPrivateValues().mapNotNull { it.optString("key").takeIf { key -> key.isNotBlank() } }

    fun putText(key: String, text: String, retention: Retention = Retention.Persistent) =
        putValue(key, text.toByteArray(Charsets.UTF_8), retention)
    fun getText(key: String): String? = getValue(key)?.toString(Charsets.UTF_8)

    fun putBlob(contentType: String, bytes: ByteArray, retention: Retention, ttlSeconds: Long? = null): String =
        api().putPrivateBlob(contentType, bytes, retention.wire, ttlSeconds).getString("blob_id")

    fun getBlob(blobId: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray = api().getPrivateBlob(blobId, maxBytes)
    fun deleteBlob(blobId: String): Boolean = api().deletePrivateBlob(blobId)
    fun renewBlob(blobId: String, retention: Retention, ttlSeconds: Long? = null) {
        api().renewPrivateBlob(blobId, retention.wire, ttlSeconds)
    }

    /**
     * Named large objects use an encrypted small index -> opaque daemon blob id. Semantic names
     * therefore never appear in local filenames, while profile/media-sized objects stay chunked.
     */
    @Synchronized
    fun putNamedBlob(
        namespace: String,
        name: String,
        contentType: String,
        bytes: ByteArray,
        retention: Retention = Retention.Persistent,
    ): String {
        val indexKey = "$namespace/index"
        val index = JSONObject(getText(indexKey) ?: "{}")
        val previous = index.optString(name).takeIf { it.isNotBlank() }
        val next = putBlob(contentType, bytes, retention)
        try {
            index.put(name, next)
            putText(indexKey, index.toString())
        } catch (t: Throwable) {
            runCatching { deleteBlob(next) }
            throw t
        }
        previous?.takeIf { it != next }?.let { runCatching { deleteBlob(it) } }
        return next
    }

    @Synchronized
    fun getNamedBlob(namespace: String, name: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
        val indexKey = "$namespace/index"
        val index = JSONObject(getText(indexKey) ?: "{}")
        val blobId = index.optString(name).takeIf { it.isNotBlank() } ?: return null
        return try {
            getBlob(blobId, maxBytes)
        } catch (t: Throwable) {
            // Cache blobs may be removed by the daemon LRU. Self-heal stale pointers instead of
            // making one pruned object permanently poison the application's index.
            if (t.message.orEmpty().contains("not found", ignoreCase = true)) {
                index.remove(name)
                putText(indexKey, index.toString())
                null
            } else throw t
        }
    }

    @Synchronized
    fun namedBlobId(namespace: String, name: String): String? =
        JSONObject(getText("$namespace/index") ?: "{}").optString(name).takeIf { it.isNotBlank() }

    @Synchronized
    fun listNamedBlobs(namespace: String): List<String> {
        val index = JSONObject(getText("$namespace/index") ?: "{}")
        return index.keys().asSequence().toList().sorted()
    }

    @Synchronized
    fun deleteNamedBlob(namespace: String, name: String): Boolean {
        val indexKey = "$namespace/index"
        val index = JSONObject(getText(indexKey) ?: "{}")
        val blobId = index.optString(name).takeIf { it.isNotBlank() } ?: return false
        index.remove(name)
        putText(indexKey, index.toString())
        runCatching { deleteBlob(blobId) }
        return true
    }

    @Synchronized
    fun renewNamedBlob(namespace: String, name: String, retention: Retention) {
        namedBlobId(namespace, name)?.let { renewBlob(it, retention) }
    }

    fun migrateLegacyNamedBlob(namespace: String, name: String, file: File, contentType: String): Boolean {
        if (!attached || !file.isFile) return false
        return runCatching {
            if (namedBlobId(namespace, name) == null) {
                putNamedBlob(namespace, name, contentType, file.readBytes(), Retention.Persistent)
            }
            check(file.delete() || !file.exists()) { "could not remove legacy plaintext ${file.name}" }
            true
        }.getOrDefault(false)
    }

    /** Move a legacy plaintext value only after a successful encrypted daemon write. */
    fun migrateLegacyFile(key: String, file: File, retention: Retention = Retention.Persistent): Boolean {
        if (!attached || !file.isFile) return false
        return runCatching {
            if (getValue(key) == null) putValue(key, file.readBytes(), retention)
            check(file.delete() || !file.exists()) { "could not remove legacy plaintext ${file.name}" }
            true
        }.getOrDefault(false)
    }

    companion object {
        @Volatile private var instance: PrivateVault? = null
        fun get(context: Context): PrivateVault = instance ?: synchronized(this) {
            instance ?: PrivateVault(context.applicationContext).also { instance = it }
        }
    }
}
