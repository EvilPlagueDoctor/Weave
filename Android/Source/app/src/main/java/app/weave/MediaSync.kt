package app.weave

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Largest media blob this app will pull. Comfortably above a 1080px WebP at quality 80. */
const val MAX_REMOTE_IMAGE_BYTES = 1_500_000

/** How many media fetches may be in flight at once. */
private const val MEDIA_FETCH_PERMITS = 2

private const val IMAGE_CONTENT_TYPE = "image/webp"

/** Every image element in the document, in page order. */
fun ProfileDocument.imageElements(): List<Element> = buildList {
    pages.forEach { page ->
        fun walk(e: Element) {
            if (e.type == ElementType.Media && e.mediaKind == MediaKind.Image) add(e)
            e.children.forEach(::walk)
        }
        walk(page.root)
    }
}

fun ProfileDocument.imageHashes(): Set<String> =
    imageElements().mapNotNull { it.mediaContentHash.takeIf(String::isNotBlank) }.toSet()

/**
 * Publishes any image that only exists on this device.
 *
 * Images are uploaded before the profile payload, because the payload records each image's
 * record key — publishing the page first would announce a document pointing at blobs that do
 * not exist yet, and every visitor in that window would see holes.
 *
 * Uploads are skipped when an element already carries a record key, so republishing an
 * unchanged page costs nothing.
 */
suspend fun publishPendingMedia(
    doc: ProfileDocument,
    media: LocalMediaStore,
    controller: SocialNetworkController,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Result<Int> {
    val pending = doc.imageElements().filter {
        it.mediaContentHash.isNotBlank() && it.mediaRecordKey.isBlank()
    }
    if (pending.isEmpty()) return Result.success(0)

    val storageGeneration = media.vaultGeneration()
    if (!media.isVaultGenerationCurrent(storageGeneration)) {
        return Result.failure(IllegalStateException("The active VeilKnit account changed before media publishing began."))
    }

    var done = 0
    onProgress(0, pending.size)

    // One upload per distinct hash: the same picture used on two pages is one blob.
    val uploadedByHash = mutableMapOf<String, String>()

    for (element in pending) {
        val hash = element.mediaContentHash
        uploadedByHash[hash]?.let {
            element.mediaRecordKey = it
            onProgress(++done, pending.size)
            continue
        }
        if (!media.isVaultGenerationCurrent(storageGeneration)) {
            return Result.failure(IllegalStateException("The active VeilKnit account changed while media was publishing."))
        }
        val bytes = media.bytesFor(hash, storageGeneration)
            ?: return Result.failure(IllegalStateException("An image on this profile is missing from this account's private vault. Re-add it and publish again."))

        val blob = controller.uploadMedia(IMAGE_CONTENT_TYPE, bytes)
            ?: return Result.failure(IllegalStateException("Couldn't upload an image. Check the daemon connection and try again."))

        if (!media.isVaultGenerationCurrent(storageGeneration)) {
            return Result.failure(IllegalStateException("The active VeilKnit account changed while media was publishing."))
        }

        val recordKey = blob.optString("root_record_key")
        if (recordKey.isBlank()) {
            return Result.failure(IllegalStateException("The daemon accepted an image but returned no record key."))
        }
        // Trust the daemon's hash over the local one: it is what other people will verify.
        blob.optString("sha256_hex").takeIf { it.isNotBlank() }?.let { element.mediaContentHash = it }
        element.mediaRecordKey = recordKey
        uploadedByHash[hash] = recordKey
        if (!media.recordBlob(hash, blob.optString("blob_id"), recordKey, storageGeneration)) {
            return Result.failure(IllegalStateException("The active VeilKnit account changed while media was publishing."))
        }
        onProgress(++done, pending.size)
    }
    return Result.success(done)
}

/**
 * Fetches images referenced by whatever the viewer is currently showing.
 *
 * Requests are served FIFO behind a small permit pool. The daemon cannot cancel a read in
 * progress, so the queue's job is to avoid starting work that is already stale: [setWanted]
 * narrows the set as the person moves, and anything no longer wanted is dropped before it
 * reaches the daemon rather than being cancelled after.
 */
@Stable
class MediaLoader(
    private val scope: CoroutineScope,
    private val media: LocalMediaStore,
    private val controller: SocialNetworkController,
) {
    /** Bumped whenever an image lands, so composables reading it re-render. */
    var revision by mutableIntStateOf(0)
        private set

    private val permits = Semaphore(MEDIA_FETCH_PERMITS)
    private val inFlight = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    /** Element hashes the loader should still bother with. Set as the viewer moves. */
    private var wanted: Set<String> = emptySet()

    fun setWanted(hashes: Set<String>) {
        synchronized(inFlight) { wanted = hashes }
    }

    /**
     * Returns the bitmap if it is on disk, and otherwise starts a fetch. Safe to call from a
     * draw pass — the disk hit is cached and the fetch happens off-thread.
     */
    fun lookup(element: Element): ImageBitmap? {
        @Suppress("UNUSED_EXPRESSION") revision // read so recomposition is tied to arrivals
        val hash = element.mediaContentHash
        if (hash.isBlank()) return null
        media.bitmapFor(hash)?.let { return it }
        request(hash, element.mediaRecordKey)
        return null
    }

    private fun request(hash: String, recordKey: String) {
        if (recordKey.isBlank()) return
        val storageGeneration = media.vaultGeneration()
        if (!media.isVaultGenerationCurrent(storageGeneration)) return
        synchronized(inFlight) {
            if (hash in inFlight || hash in failed) return
            inFlight.add(hash)
        }
        scope.launch {
            try {
                permits.withPermit {
                    // Re-check after queueing: the person may have moved on or switched accounts.
                    if (!media.isVaultGenerationCurrent(storageGeneration)) return@withPermit
                    val stillWanted = synchronized(inFlight) { wanted.isEmpty() || hash in wanted }
                    if (!stillWanted) return@withPermit
                    if (media.bitmapFor(hash) != null) return@withPermit

                    val bytes = controller.downloadMedia(
                        rootRecordKey = recordKey,
                        expectedSha256Hex = hash,
                        maxBytes = MAX_REMOTE_IMAGE_BYTES,
                        keepGoing = {
                            media.isVaultGenerationCurrent(storageGeneration) &&
                                synchronized(inFlight) { wanted.isEmpty() || hash in wanted }
                        },
                    )
                    if (bytes == null) {
                        if (media.isVaultGenerationCurrent(storageGeneration)) {
                            synchronized(inFlight) { failed.add(hash) }
                        }
                        return@withPermit
                    }
                    if (!media.storeVerifiedBytes(hash, bytes, storageGeneration)) return@withPermit
                    revision++
                }
            } finally {
                synchronized(inFlight) { inFlight.remove(hash) }
            }
        }
    }

    /** Lets a failed image be retried, e.g. after the connection comes back. */
    fun clearFailures() {
        synchronized(inFlight) { failed.clear() }
        revision++
    }
}
