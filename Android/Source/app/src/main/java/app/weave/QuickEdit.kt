package app.weave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device image store.
 *
 * Every imported image is decoded, downscaled, and re-encoded to WebP before it is written.
 * That round trip is what strips EXIF, GPS tags, ICC oddities, appended payloads and
 * polyglot-file tricks: whatever the source file contained, what lands on disk is pixels
 * this process produced.
 *
 * NOTE: the bytes are local only. Publishing them still needs a daemon blob upload, and the
 * element's mediaRecordKey stays blank until that exists.
 */
/**
 * Longest edge of a stored image, in pixels. Sized for a phone viewport with room for a
 * pinch-zoom, not for archival quality — these bytes get published, and every extra pixel is
 * bandwidth for everyone who loads the page.
 */
const val MAX_IMAGE_EDGE_PX = 1080

/** WebP lossy quality. Above roughly 80 the file grows faster than the picture improves. */
const val IMAGE_QUALITY = 80

class LocalMediaStore(context: Context) : PrivateVault.Participant {
    private val appContext = context.applicationContext
    private val vault = PrivateVault.get(appContext)
    private val legacyDir = File(appContext.filesDir, "media")
    private val legacyBlobIndex = File(legacyDir, "blob-index.json")
    private val legacyLibrary = File(legacyDir, "library.json")

    private val cache = object : LinkedHashMap<String, ImageBitmap>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
    }
    private val missing = mutableSetOf<String>()

    data class Stored(val contentHash: String, val width: Int, val height: Int)
    data class AudioStored(val contentHash: String, val durationMs: Long)
    data class SavedImage(val contentHash: String, val width: Int, val height: Int, val description: String)

    init { vault.register(this) }

    override fun onVaultAttached() {
        // Metadata first, then image bytes. Legacy files are removed only after the encrypted
        // daemon write succeeds. Existing images are conservatively migrated as Persistent so
        // an upgrade can never discard something the person expected to keep.
        vault.migrateLegacyFile("media/network-index.json", legacyBlobIndex)
        vault.migrateLegacyFile("media/library.json", legacyLibrary)
        legacyDir.listFiles { f -> f.isFile && f.extension.equals("webp", true) }?.forEach { file ->
            vault.migrateLegacyNamedBlob("media/files", file.nameWithoutExtension, file, "image/webp")
        }
        runCatching { if (legacyDir.isDirectory && legacyDir.list().isNullOrEmpty()) legacyDir.delete() }
        synchronized(cache) { cache.clear(); missing.clear() }
    }

    override fun onVaultDetached() {
        synchronized(cache) { cache.clear(); missing.clear() }
    }

    suspend fun importImage(uri: Uri, maxEdge: Int = MAX_IMAGE_EDGE_PX, quality: Int = IMAGE_QUALITY): Stored? =
        withContext(Dispatchers.IO) {
            val generation = vault.generation()
            runCatching {
                if (!vault.attached) return@runCatching null
                val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxEdge) {
                        val scale = maxEdge.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
                val format = when {
                    decoded.hasAlpha() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSLESS
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
                    else -> {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                }
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    decoded.compress(format, if (decoded.hasAlpha()) 100 else quality, out)
                    out.toByteArray()
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val stored = Stored(hash, decoded.width, decoded.height)
                val committed = vault.withGeneration(generation) {
                    vault.putNamedBlob("media/files", hash, "image/webp", bytes, PrivateVault.Retention.Persistent)
                    true
                } == true
                decoded.recycle()
                if (!committed) return@runCatching null
                synchronized(cache) { cache.remove(hash); missing.remove(hash) }
                stored
            }.getOrNull()
        }

    suspend fun importBitmap(bitmap: Bitmap, maxEdge: Int = MAX_IMAGE_EDGE_PX, quality: Int = IMAGE_QUALITY): Stored? =
        withContext(Dispatchers.IO) { storeSanitizedBitmap(bitmap, maxEdge, quality) }

    suspend fun importAudio(uri: Uri): AudioStored? = withContext(Dispatchers.IO) {
        val generation = vault.generation()
        val sanitized = sanitizeAudioToM4a(appContext, uri) ?: return@withContext null
        val hash = MessageDigest.getInstance("SHA-256").digest(sanitized.bytes)
            .joinToString("") { "%02x".format(it) }
        val committed = vault.withGeneration(generation) {
            vault.putNamedBlob(
                "media/audio",
                hash,
                "audio/mp4",
                sanitized.bytes,
                PrivateVault.Retention.Persistent,
            )
            true
        } == true
        if (!committed) null else AudioStored(hash, sanitized.durationMs)
    }

    fun audioBytesFor(contentHash: String, expectedGeneration: Long? = null): ByteArray? {
        if (contentHash.isBlank() || !vault.attached) return null
        return runCatching {
            if (expectedGeneration == null) {
                vault.getNamedBlob("media/audio", contentHash, MAX_SANITIZED_AUDIO_BYTES)
            } else {
                vault.withGeneration(expectedGeneration) {
                    vault.getNamedBlob("media/audio", contentHash, MAX_SANITIZED_AUDIO_BYTES)
                }
            }
        }.getOrNull()
    }

    fun storeVerifiedAudioBytes(
        contentHash: String,
        bytes: ByteArray,
        expectedGeneration: Long? = null,
    ): Boolean = runCatching {
        if (bytes.size > MAX_SANITIZED_AUDIO_BYTES) return@runCatching false
        val generation = expectedGeneration ?: vault.generation()
        vault.withGeneration(generation) {
            vault.putNamedBlob(
                "media/audio",
                contentHash,
                "audio/mp4",
                bytes,
                PrivateVault.Retention.Cache,
            )
            true
        } == true
    }.getOrDefault(false)

    suspend fun importBytes(bytes: ByteArray, maxEdge: Int = MAX_IMAGE_EDGE_PX, quality: Int = IMAGE_QUALITY): Stored? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxEdge) {
                        val scale = maxEdge.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
                storeSanitizedBitmap(decoded, maxEdge, quality).also { decoded.recycle() }
            }.getOrNull()
        }

    suspend fun thumbnailBase64For(
        contentHash: String,
        maxEdge: Int = 220,
        quality: Int = 60,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val sourceBytes = bytesFor(contentHash) ?: return@runCatching null
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(sourceBytes))
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            try {
                var attemptQuality = quality.coerceIn(20, 95)
                var encoded: ByteArray
                while (true) {
                    encoded = java.io.ByteArrayOutputStream().use { out ->
                        val format = when {
                            decoded.hasAlpha() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSLESS
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
                            else -> {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                        }
                        decoded.compress(format, if (decoded.hasAlpha()) 100 else attemptQuality, out)
                        out.toByteArray()
                    }
                    if (encoded.size <= WeaveMessage.MAX_THUMBNAIL_BYTES || attemptQuality <= 25) break
                    attemptQuality -= 10
                }
                if (encoded.size > WeaveMessage.MAX_THUMBNAIL_BYTES) null
                else WeaveMessage.thumbnailToBase64(encoded)
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    /** Re-encode pixels only. Lossless WebP is used whenever alpha is present. */
    private fun storeSanitizedBitmap(sourceBitmap: Bitmap, maxEdge: Int, quality: Int): Stored? {
        if (!vault.attached) return null
        val generation = vault.generation()
        var bitmap = sourceBitmap
        val longest = max(bitmap.width, bitmap.height)
        if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        val format = when {
            bitmap.hasAlpha() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSLESS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        val encodeQuality = if (bitmap.hasAlpha()) 100 else quality
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            check(bitmap.compress(format, encodeQuality, out)) { "Could not encode image" }
            out.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val stored = Stored(hash, bitmap.width, bitmap.height)
        val committed = vault.withGeneration(generation) {
            vault.putNamedBlob("media/files", hash, "image/webp", bytes, PrivateVault.Retention.Persistent)
            true
        } == true
        if (bitmap !== sourceBitmap) bitmap.recycle()
        if (!committed) return null
        synchronized(cache) { cache.remove(hash); missing.remove(hash) }
        return stored
    }

    fun networkBlobIds(): List<String> = runCatching {
        if (!vault.attached) return emptyList()
        val root = org.json.JSONObject(vault.getText("media/network-index.json") ?: "{}")
        root.keys().asSequence().mapNotNull { key ->
            root.optJSONObject(key)?.optString("blob_id")?.takeIf { it.isNotBlank() }
        }.distinct().toList()
    }.getOrElse { emptyList() }

    fun clearNetworkMappings() {
        if (vault.attached) runCatching { vault.putText("media/network-index.json", "{}") }
    }

    fun vaultGeneration(): Long = vault.generation()

    fun isVaultGenerationCurrent(generation: Long): Boolean =
        vault.withGeneration(generation) { true } == true

    fun bytesFor(contentHash: String, expectedGeneration: Long? = null): ByteArray? {
        if (contentHash.isBlank() || !vault.attached) return null
        return runCatching {
            if (expectedGeneration == null) {
                vault.getNamedBlob("media/files", contentHash, 8 * 1024 * 1024)
            } else {
                vault.withGeneration(expectedGeneration) {
                    vault.getNamedBlob("media/files", contentHash, 8 * 1024 * 1024)
                }
            }
        }.getOrNull()
    }

    /** Cheap enough after the first decode; encrypted bytes are range-read by the daemon. */
    fun bitmapFor(contentHash: String): ImageBitmap? {
        if (contentHash.isBlank()) return null
        synchronized(cache) {
            cache[contentHash]?.let { return it }
            if (contentHash in missing) return null
        }
        val decoded = runCatching {
            val bytes = bytesFor(contentHash) ?: return@runCatching null
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }.asImageBitmap()
        }.getOrNull()
        synchronized(cache) {
            if (decoded == null) missing.add(contentHash) else cache[contentHash] = decoded
        }
        return decoded
    }

    /** Remote downloads are disposable encrypted Cache blobs; the daemon may LRU-prune them. */
    fun storeVerifiedBytes(contentHash: String, bytes: ByteArray, expectedGeneration: Long? = null): Boolean =
        runCatching {
            val generation = expectedGeneration ?: vault.generation()
            val committed = vault.withGeneration(generation) {
                val retention = if (isSaved(contentHash)) PrivateVault.Retention.Persistent else PrivateVault.Retention.Cache
                vault.putNamedBlob("media/files", contentHash, "image/webp", bytes, retention)
                true
            } == true
            if (committed) synchronized(cache) { cache.remove(contentHash); missing.remove(contentHash) }
            committed
        }.getOrDefault(false)

    fun saveToLibrary(contentHash: String, width: Int, height: Int, description: String) {
        if (contentHash.isBlank() || !vault.attached) return
        runCatching {
            val library = org.json.JSONObject(vault.getText("media/library.json") ?: "{}")
            library.put(
                contentHash,
                org.json.JSONObject()
                    .put("w", width).put("h", height)
                    .put("desc", description).put("saved_at", System.currentTimeMillis())
            )
            vault.putText("media/library.json", library.toString())
            // Saving a previously downloaded cache image makes it durable without re-uploading it.
            vault.renewNamedBlob("media/files", contentHash, PrivateVault.Retention.Persistent)
        }
    }

    fun library(): List<SavedImage> = runCatching {
        if (!vault.attached) return emptyList()
        val root = org.json.JSONObject(vault.getText("media/library.json") ?: "{}")
        root.keys().asSequence().mapNotNull { key ->
            val entry = root.optJSONObject(key) ?: return@mapNotNull null
            SavedImage(key, entry.optInt("w", 1), entry.optInt("h", 1), entry.optString("desc"))
        }.toList()
    }.getOrElse { emptyList() }

    fun isSaved(contentHash: String): Boolean = library().any { it.contentHash == contentHash }

    /** Explicit Gallery export is intentionally outside the private vault: the person asked for it. */
    fun exportToGallery(context: Context, contentHash: String, displayName: String): Boolean = runCatching {
        val bytes = bytesFor(contentHash) ?: return false
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$displayName.webp")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/webp")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Weave")
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        true
    }.getOrElse { false }

    /** Network/DHT blob mapping is user-derived metadata, so it is encrypted too. */
    fun recordBlob(
        contentHash: String,
        blobId: String,
        recordKey: String,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (contentHash.isBlank() || blobId.isBlank() || !vault.attached) return false
        return runCatching {
            val generation = expectedGeneration ?: vault.generation()
            vault.withGeneration(generation) {
                val index = org.json.JSONObject(vault.getText("media/network-index.json") ?: "{}")
                index.put(contentHash, org.json.JSONObject().put("blob_id", blobId).put("record_key", recordKey))
                vault.putText("media/network-index.json", index.toString())
                true
            } == true
        }.getOrDefault(false)
    }

    fun blobIdFor(contentHash: String): String? = runCatching {
        org.json.JSONObject(vault.getText("media/network-index.json") ?: "{}")
            .optJSONObject(contentHash)?.optString("blob_id")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun pruneUnreferenced(doc: ProfileDocument): Int {
        if (!vault.attached) return 0
        val referenced = buildSet {
            doc.pages.forEach { page ->
                fun walk(e: Element) {
                    if (e.type == ElementType.Media && e.mediaContentHash.isNotBlank()) add(e.mediaContentHash)
                    e.children.forEach(::walk)
                }
                walk(page.root)
            }
        }
        var removed = 0
        vault.listNamedBlobs("media/files").forEach { hash ->
            if (hash !in referenced && !isSaved(hash) && vault.deleteNamedBlob("media/files", hash)) {
                removed++
                synchronized(cache) { cache.remove(hash); missing.remove(hash) }
            }
        }
        val referencedAudio = buildSet {
            doc.pages.forEach { page ->
                fun walk(e: Element) {
                    if (e.type == ElementType.Media &&
                        e.mediaKind == MediaKind.Audio &&
                        e.mediaContentHash.isNotBlank()
                    ) add(e.mediaContentHash)
                    e.children.forEach(::walk)
                }
                walk(page.root)
            }
        }
        vault.listNamedBlobs("media/audio").forEach { hash ->
            if (hash !in referencedAudio && vault.deleteNamedBlob("media/audio", hash)) removed++
        }
        return removed
    }
}

// ---------------------------------------------------------------------------
// Simple editor
// ---------------------------------------------------------------------------

/** One item in the simple editor. Text and images only — no z-order, no nesting. */
sealed interface QuickBlock {
    val id: String

    data class Heading(override val id: String, val text: String) : QuickBlock
    data class Body(override val id: String, val text: String) : QuickBlock
    data class Picture(
        override val id: String,
        val contentHash: String,
        val width: Int,
        val height: Int,
        val caption: String,
    ) : QuickBlock
    data class Audio(
        override val id: String,
        val contentHash: String,
        val durationMs: Long,
        val title: String,
    ) : QuickBlock
}

/**
 * The aspect ratio window VSPF enforces. ProfileCodec.validate rejects anything outside it,
 * and an invalid document cannot be encoded — which means it can neither be saved nor
 * published. Keep these in sync with VspfLimits.
 */
const val MIN_PAGE_ASPECT = .20f
const val MAX_PAGE_ASPECT = 1.20f

private const val MARGIN_FRAC = .07f
private const val GAP_DP = 14f
private const val HEADING_SP = 50f
private const val BODY_SP = 25f
private const val HEADING_DETECT_MIN_SP = 26f
private const val PROFILE_IMAGE_HEIGHT_DP = 180f
private const val PROFILE_AUDIO_HEIGHT_DP = 72f
private const val CAPTION_SP = 12f

/**
 * Reads the simple-editor block list back out of a page.
 *
 * Anything the advanced editor added that doesn't fit the simple model (nested blocks,
 * stamps, widgets, links) is skipped rather than mangled, which is why the simple editor
 * warns before saving over a page it can't fully represent.
 */
fun blocksFromPage(page: Page): List<QuickBlock> = page.root.children
    .sortedBy { it.rect.y }
    .mapNotNull { e ->
        when {
            e.type == ElementType.Text && e.fontSize >= HEADING_DETECT_MIN_SP -> QuickBlock.Heading(e.id, e.text)
            e.type == ElementType.Text -> QuickBlock.Body(e.id, e.text)
            e.type == ElementType.Media && e.mediaKind == MediaKind.Image ->
                QuickBlock.Picture(
                    e.id, e.mediaContentHash,
                    e.intrinsicWidth.toInt().coerceAtLeast(1),
                    e.intrinsicHeight.toInt().coerceAtLeast(1),
                    e.mediaDescription
                )
            e.type == ElementType.Media && e.mediaKind == MediaKind.Audio ->
                QuickBlock.Audio(
                    e.id,
                    e.mediaContentHash,
                    0L,
                    e.mediaTitle.ifBlank { "Audio" },
                )
            else -> null
        }
    }

/**
 * True when the laid-out content is taller than a single page may be. The caller should tell
 * the person to split the page rather than silently clipping the overflow.
 */
fun blocksOverflowPage(blocks: List<QuickBlock>, referenceWidthDp: Float, densityScale: Float): Boolean {
    val contentWidthDp = referenceWidthDp * (1f - 2 * MARGIN_FRAC)
    val total = referenceWidthDp * MARGIN_FRAC * 2 +
        GAP_DP * (blocks.size - 1).coerceAtLeast(0) +
        blocks.sumOf { block ->
            when (block) {
                is QuickBlock.Heading -> measureTextHeightDp(block.text, HEADING_SP, contentWidthDp, true, densityScale)
                is QuickBlock.Body -> measureTextHeightDp(block.text, BODY_SP, contentWidthDp, false, densityScale)
                is QuickBlock.Picture -> PROFILE_IMAGE_HEIGHT_DP
            is QuickBlock.Audio -> PROFILE_AUDIO_HEIGHT_DP
            }.toDouble()
        }.toFloat()
    return total > referenceWidthDp / MIN_PAGE_ASPECT
}

/** True when the page contains things the simple editor would silently drop. */
fun pageHasAdvancedContent(page: Page): Boolean = page.root.children.any { e ->
    e.children.isNotEmpty() ||
        e.type == ElementType.Stamp || e.type == ElementType.Widget ||
        e.type == ElementType.Link || e.type == ElementType.Button ||
        (e.type == ElementType.Media && e.mediaKind == MediaKind.Video)
}

private fun measureTextHeightDp(text: String, sizeSp: Float, widthDp: Float, bold: Boolean, densityScale: Float): Float {
    if (text.isBlank()) return sizeSp * 1.4f
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * densityScale
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val widthPx = max(1, (widthDp * densityScale).roundToInt())
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
    return layout.height / densityScale
}

/**
 * Lays the blocks out as a single vertical stack and writes them into [page].
 *
 * The page keeps fixed width and grows downward, which is exactly the viewer's model.
 * [referenceWidthDp] is the width the author is looking at: element rects are fractions,
 * but font sizes are in dp, so the layout is exact at this width and drifts a little at
 * others. Generous gaps absorb the drift.
 */
fun applyBlocksToPage(page: Page, blocks: List<QuickBlock>, referenceWidthDp: Float, densityScale: Float) {
    val contentWidthDp = referenceWidthDp * (1f - 2 * MARGIN_FRAC)

    val heights = blocks.map { block ->
        when (block) {
            is QuickBlock.Heading -> measureTextHeightDp(block.text, HEADING_SP, contentWidthDp, true, densityScale)
            is QuickBlock.Body -> measureTextHeightDp(block.text, BODY_SP, contentWidthDp, false, densityScale)
            is QuickBlock.Picture -> PROFILE_IMAGE_HEIGHT_DP
            is QuickBlock.Audio -> PROFILE_AUDIO_HEIGHT_DP
        }
    }

    val topPadDp = referenceWidthDp * MARGIN_FRAC
    val contentDp = topPadDp * 2 + heights.sum() + GAP_DP * (blocks.size - 1).coerceAtLeast(0)

    // VSPF only accepts an aspect ratio in 0.20..1.20, so the page height has a hard floor
    // and ceiling. Clamp the HEIGHT and derive the ratio from it — clamping the ratio alone
    // would leave the element fractions computed against a height the page no longer has,
    // which stretches or overflows the content.
    val layoutHeightDp = contentDp.coerceIn(
        referenceWidthDp / MAX_PAGE_ASPECT,
        referenceWidthDp / MIN_PAGE_ASPECT,
    )
    page.aspectRatio = (referenceWidthDp / layoutHeightDp).coerceIn(MIN_PAGE_ASPECT, MAX_PAGE_ASPECT)
    val totalDp = layoutHeightDp

    val children = mutableListOf<Element>()
    var cursor = topPadDp
    blocks.forEachIndexed { index, block ->
        val h = heights[index]
        val (rectX, rectWidth) = if (block is QuickBlock.Picture) {
            val naturalWidthDp = PROFILE_IMAGE_HEIGHT_DP * block.width.toFloat() / block.height.toFloat().coerceAtLeast(1f)
            val imageWidthDp = naturalWidthDp.coerceAtMost(contentWidthDp)
            val widthFrac = imageWidthDp / referenceWidthDp
            ((1f - widthFrac) / 2f) to widthFrac
        } else {
            MARGIN_FRAC to (1f - 2 * MARGIN_FRAC)
        }
        val rect = RectSpec(
            x = rectX,
            y = cursor / totalDp,
            width = rectWidth,
            height = h / totalDp,
            zIndex = index,
        )
        children += when (block) {
            is QuickBlock.Heading -> Element(
                type = ElementType.Text, id = block.id, name = "Heading",
                rect = rect, text = block.text, fontSize = HEADING_SP, bold = true,
                textArgb = readableTextArgb(page), textAlign = TextAlignMode.Center,
            )

            is QuickBlock.Body -> Element(
                type = ElementType.Text, id = block.id, name = "Text",
                rect = rect, text = block.text, fontSize = BODY_SP,
                textArgb = readableTextArgb(page), textAlign = TextAlignMode.Left,
            )

            is QuickBlock.Picture -> Element(
                type = ElementType.Media, id = block.id, name = "Image",
                rect = rect, mediaKind = MediaKind.Image,
                mediaContentHash = block.contentHash,
                intrinsicWidth = block.width.toLong(), intrinsicHeight = block.height.toLong(),
                mediaTitle = block.caption.ifBlank { "Image" },
                mediaDescription = block.caption,
            )

            is QuickBlock.Audio -> Element(
                type = ElementType.Media, id = block.id, name = "Audio",
                rect = rect, mediaKind = MediaKind.Audio,
                mediaContentHash = block.contentHash,
                intrinsicWidth = 1, intrinsicHeight = 1,
                mediaTitle = block.title.ifBlank { "Audio" },
                mediaDescription = "",
            )
        }
        cursor += h + GAP_DP
    }
    page.root.children.clear()
    page.root.children.addAll(children)
}

private fun readableTextArgb(page: Page): Int {
    val bg = page.root.background
    val argb = if (bg.kind == BackgroundKind.Solid) bg.solidArgb else bg.stops.firstOrNull()?.argb ?: 0xFFFFFFFF.toInt()
    val r = (argb shr 16) and 0xff
    val g = (argb shr 8) and 0xff
    val b = argb and 0xff
    return if ((0.299 * r + 0.587 * g + 0.114 * b) < 140) 0xFFF5F5F5.toInt() else 0xFF111827.toInt()
}

/**
 * The simple editor: the first-run experience, kept around permanently.
 *
 * Everything is a vertical stack of text and pictures. No coordinates, no z-order, no
 * hierarchy tree. The advanced editor is still there for people who want it.
 */
@Composable
fun QuickEditScreen(
    state: EditorState,
    media: LocalMediaStore,
    controller: SocialNetworkController,
    pageIndex: Int,
    onOpenAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var currentPage by remember { mutableIntStateOf(pageIndex.coerceIn(0, state.doc.pages.lastIndex)) }
    val page = state.doc.pages.getOrNull(currentPage) ?: state.doc.pages.first()

    var blocks by remember(page.id) { mutableStateOf(blocksFromPage(page)) }
    var profileName by remember { mutableStateOf(state.doc.profileName) }
    var showAdvancedWarning by remember(page.id) { mutableStateOf(pageHasAdvancedContent(page)) }
    var importing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var backgroundRevision by remember { mutableIntStateOf(0) }
    var showLibrary by remember { mutableStateOf(false) }
    var showDhtImport by remember { mutableStateOf(false) }
    var dhtLocation by remember { mutableStateOf("") }
    var dhtImportError by remember { mutableStateOf<String?>(null) }
    val invalidDhtImageMessage = tr("Couldn't read a valid image from that DHT location.")
    val scroll = rememberScrollState()

    // Snapshot on entry so leaving without saving really can put everything back, including
    // pages added or renamed during the session.
    val entrySnapshot = remember { state.doc.deepCopy() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val stored = media.importImage(uri)
            importing = false
            if (stored != null) {
                blocks = blocks + QuickBlock.Picture(makeId("img"), stored.contentHash, stored.width, stored.height, "")
            }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val stored = media.importAudio(uri)
            importing = false
            if (stored != null) {
                blocks = blocks + QuickBlock.Audio(
                    makeId("audio"),
                    stored.contentHash,
                    stored.durationMs,
                    "Audio",
                )
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val stored = media.importBitmap(bitmap)
            importing = false
            if (stored != null) {
                blocks = blocks + QuickBlock.Picture(makeId("img"), stored.contentHash, stored.width, stored.height, "")
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val referenceWidthDp = maxWidth.value

        // replaceDocument persists to the active slot, so every exit path here is durable.
        fun commit() {
            state.doc.profileName = profileName.trim().ifBlank { state.doc.profileName }
            applyBlocksToPage(page, blocks, referenceWidthDp, density)
            state.replaceDocument(state.doc)
            media.pruneUnreferenced(state.doc)
        }

        fun discardAll() {
            state.replaceDocument(entrySnapshot)
            media.pruneUnreferenced(entrySnapshot)
        }

        fun switchPage(target: Int) {
            if (target == currentPage) return
            commit()
            currentPage = target.coerceIn(0, state.doc.pages.lastIndex)
        }

        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { confirmDiscard = true }) { Text(tr("Discard")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        commit()
                        state.setPreferredEditorAdvanced(true)
                        onOpenAdvanced()
                    }) { Text(tr("Advanced")) }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = { commit(); onBack() }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(tr("Done")) }
                }
            }

            PageStrip(
                pages = state.doc.pages.map { it.name },
                current = currentPage,
                onSelect = ::switchPage,
                onAddPage = {
                    commit()
                    val template = state.doc.pages[currentPage]
                    state.doc.pages.add(
                        Page(
                            id = makeId("page"),
                            name = "Page ${state.doc.pages.size + 1}",
                            aspectRatio = template.aspectRatio,
                            root = Element(
                                type = ElementType.Block, id = makeId("root"), name = "Page",
                                rect = RectSpec(0f, 0f, 1f, 1f),
                                background = template.root.background.copy(),
                            )
                        )
                    )
                    currentPage = state.doc.pages.lastIndex
                },
                onRenamePage = { name ->
                    state.doc.pages[currentPage].name = name.take(40)
                },
                onDeletePage = {
                    if (state.doc.pages.size > 1) {
                        state.doc.pages.removeAt(currentPage)
                        currentPage = currentPage.coerceAtMost(state.doc.pages.lastIndex)
                        state.replaceDocument(state.doc)
                    }
                },
                canDelete = state.doc.pages.size > 1,
            )

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp)) {
                if (importing) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Preparing media…"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (showAdvancedWarning) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(tr("This page uses the advanced editor"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("It has stamps, widgets or layered boxes. Saving here would replace them with a simple stack."),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    commit()
                                    state.setPreferredEditorAdvanced(true)
                                    onOpenAdvanced()
                                }) { Text(tr("Open advanced")) }
                                TextButton(onClick = { showAdvancedWarning = false }) { Text(tr("Simplify anyway")) }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Profile name")) },
                    singleLine = true,
                )

                Spacer(Modifier.height(16.dp))

                BackgroundPicker(
                    current = page.root.background,
                    onChange = { page.root.background = it; backgroundRevision++ },
                )

                Spacer(Modifier.height(16.dp))

                blocks.forEachIndexed { index, block ->
                    BlockEditor(
                        block = block,
                        bitmap = (block as? QuickBlock.Picture)?.let { media.bitmapFor(it.contentHash) },
                        isFirst = index == 0,
                        isLast = index == blocks.lastIndex,
                        onChange = { updated -> blocks = blocks.toMutableList().also { it[index] = updated } },
                        onDelete = { blocks = blocks.toMutableList().also { it.removeAt(index) } },
                        onMoveUp = {
                            blocks = blocks.toMutableList().also {
                                val item = it.removeAt(index); it.add(index - 1, item)
                            }
                        },
                        onMoveDown = {
                            blocks = blocks.toMutableList().also {
                                val item = it.removeAt(index); it.add(index + 1, item)
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Keep all insertion tools on one swipeable row. On a phone, wrapping Audio or
                // DHT image onto a second line made the toolbar look like a vertical menu.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { blocks = blocks + QuickBlock.Heading(makeId("head"), "Heading") }) { Text(tr("+ Heading")) }
                    OutlinedButton(onClick = { blocks = blocks + QuickBlock.Body(makeId("text"), "") }) { Text(tr("+ Text")) }
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    ) { Text(tr("+ Image")) }
                    OutlinedButton(onClick = { audioPicker.launch("audio/*") }) { Text("+ Audio") }
                    OutlinedButton(onClick = { camera.launch(null) }) { Text(tr("+ Camera")) }
                    OutlinedButton(onClick = { dhtImportError = null; showDhtImport = true }) { Text(tr("+ DHT image")) }
                    if (media.library().isNotEmpty()) {
                        OutlinedButton(onClick = { showLibrary = true }) { Text(tr("+ Saved")) }
                    }
                }

                if (blocks.isEmpty()) {
                    Text(
                        tr("This page is empty. Add a heading, some text, or a picture."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                Text(
                    tr("Images keep transparent areas; photos are still compressed to save bandwidth."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Spacer(Modifier.height(40.dp))
            }
        }

        if (showDhtImport) {
            AlertDialog(
                onDismissRequest = { showDhtImport = false },
                title = { Text(tr("DHT image location")) },
                text = {
                    Column {
                        Text(tr("Paste a copied Weave image link or the image's raw DHT record key."), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = dhtLocation,
                            onValueChange = { dhtLocation = it.take(512); dhtImportError = null },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = false,
                            minLines = 2,
                        )
                        dhtImportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = dhtLocation.isNotBlank() && !importing,
                        onClick = {
                            importing = true
                            scope.launch {
                                val pasted = dhtLocation.trim()
                                val detected = parseDetectedLink(pasted)
                                val bytes = when (detected) {
                                    is DetectedLink.Media -> {
                                        if (detected.mediaType != WeaveObjectType.Image) null
                                        else controller.downloadMedia(
                                            rootRecordKey = detected.recordKey,
                                            expectedSha256Hex = detected.sha256,
                                            maxBytes = 8 * 1024 * 1024,
                                        )
                                    }
                                    is DetectedLink.Dht -> controller.downloadMediaByRecordKey(detected.recordKey, 8 * 1024 * 1024)
                                    else -> controller.downloadMediaByRecordKey(pasted, 8 * 1024 * 1024)
                                }
                                val stored = bytes?.let { media.importBytes(it) }
                                importing = false
                                if (stored != null) {
                                    blocks = blocks + QuickBlock.Picture(makeId("img"), stored.contentHash, stored.width, stored.height, "")
                                    dhtLocation = ""
                                    dhtImportError = null
                                    showDhtImport = false
                                } else {
                                    dhtImportError = invalidDhtImageMessage
                                }
                            }
                        },
                    ) { Text(tr("Import")) }
                },
                dismissButton = { TextButton(onClick = { showDhtImport = false }) { Text(tr("Cancel")) } },
            )
        }

        if (showLibrary) {
            val saved = remember(showLibrary) { media.library() }
            AlertDialog(
                onDismissRequest = { showLibrary = false },
                title = { Text(tr("Saved images")) },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        saved.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    blocks = blocks + QuickBlock.Picture(
                                        makeId("img"), item.contentHash, item.width, item.height, item.description
                                    )
                                    showLibrary = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                media.bitmapFor(item.contentHash)?.let { bitmap ->
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap,
                                        contentDescription = item.description.ifBlank { tr("Saved image") },
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(
                                    item.description.ifBlank { tr("Saved image") },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLibrary = false }) { Text(tr("Close")) } },
            )
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(tr("Discard changes?")) },
                text = { Text(tr("Everything you changed since opening the editor goes back to how it was. Anything already published stays published.")) },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false; discardAll(); onBack() }) { Text(tr("Discard")) }
                },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(tr("Keep editing")) } }
            )
        }
    }
}

@Composable
private fun BlockEditor(
    block: QuickBlock,
    bitmap: ImageBitmap?,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (QuickBlock) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (block) {
                        is QuickBlock.Heading -> tr("Heading")
                        is QuickBlock.Body -> tr("Text")
                        is QuickBlock.Picture -> tr("Image")
                        is QuickBlock.Audio -> "Audio"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMoveUp, enabled = !isFirst, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("\u2191") }
                TextButton(onClick = onMoveDown, enabled = !isLast, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("\u2193") }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("\u2715") }
            }

            when (block) {
                is QuickBlock.Heading -> OutlinedTextField(
                    value = block.text,
                    onValueChange = { onChange(block.copy(text = it.take(120))) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                is QuickBlock.Body -> OutlinedTextField(
                    value = block.text,
                    onValueChange = { onChange(block.copy(text = it.take(4000))) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )

                is QuickBlock.Picture -> Column {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = block.caption.ifBlank { tr("Image") },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        )
                    } else {
                        Text(
                            tr("Image data missing."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = block.caption,
                        onValueChange = { onChange(block.copy(caption = it.take(200))) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text(tr("Caption (optional)")) },
                        singleLine = true,
                    )
                }

                is QuickBlock.Audio -> Column {
                    Text(
                        "Sanitized AAC/M4A${if (block.durationMs > 0) " · ${block.durationMs / 1000}s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = block.title,
                        onValueChange = { onChange(block.copy(title = it.take(120))) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Audio title") },
                        singleLine = true,
                    )
                }
            }
        }
    }
}

/**
 * Page management for the simple editor.
 *
 * Multi-page books were reachable only from the advanced editor before this, so anyone who
 * stayed in the simple editor could never use the feature the starter profile demonstrates.
 */
@Composable
private fun PageStrip(
    pages: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    onAddPage: () -> Unit,
    onRenamePage: (String) -> Unit,
    onDeletePage: () -> Unit,
    canDelete: Boolean,
) {
    var renaming by remember { mutableStateOf(false) }
    var draftName by remember(current) { mutableStateOf(pages.getOrElse(current) { "" }) }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Pages"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { renaming = true; draftName = pages.getOrElse(current) { "" } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("Rename"), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDeletePage, enabled = canDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("Delete"), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                pages.forEachIndexed { index, name ->
                    val selected = index == current
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            name.ifBlank { "Page ${index + 1}" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { onSelect(index) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
                OutlinedButton(onClick = onAddPage, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("+", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(tr("Rename page")) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(40) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onRenamePage(draftName.trim()); renaming = false }) { Text(tr("Rename")) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(tr("Cancel")) } }
        )
    }
}

/**
 * Background chooser for the simple editor.
 *
 * Deliberately narrower than the advanced editor's: one colour, or two at a fixed midpoint
 * with either a hard edge or a fade, in one of four directions. Anything past that is what
 * the advanced editor is for.
 */
@Composable
private fun BackgroundPicker(current: BackgroundSpec, onChange: (BackgroundSpec) -> Unit) {
    val (initialStyle, initialColours, initialDirection) = remember(current) { current.toStyleChoices() }
    var style by remember(current) { mutableStateOf(initialStyle) }
    var first by remember(current) { mutableIntStateOf(initialColours.first) }
    var second by remember(current) { mutableIntStateOf(initialColours.second) }
    var direction by remember(current) { mutableStateOf(initialDirection) }

    fun push() = onChange(backgroundFor(style, first, second, direction))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Page background"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundStyle.entries.forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = { style = option; push() },
                        label = {
                            Text(
                                when (option) {
                                    BackgroundStyle.Solid -> tr("One colour")
                                    BackgroundStyle.HardSplit -> tr("Split")
                                    BackgroundStyle.SmoothFade -> tr("Fade")
                                }
                            )
                        },
                    )
                }
            }

            Text(
                if (style == BackgroundStyle.Solid) tr("Colour") else tr("First colour"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            ColourRow(selected = first) { first = it; push() }

            if (style != BackgroundStyle.Solid) {
                Text(
                    tr("Second colour"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                ColourRow(selected = second) { second = it; push() }

                Text(
                    tr("Direction"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BackgroundDirection.entries.forEach { option ->
                        FilterChip(
                            selected = direction == option,
                            onClick = { direction = option; push() },
                            label = { Text(tr(option.label)) },
                        )
                    }
                }
            }
        }
    }
}
