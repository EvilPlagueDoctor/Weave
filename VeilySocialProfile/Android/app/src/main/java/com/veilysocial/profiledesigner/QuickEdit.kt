package com.veilysocial.profiledesigner

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
class LocalMediaStore(context: Context) {
    private val dir = File(context.filesDir, "media").apply { mkdirs() }
    private val appContext = context.applicationContext

    /**
     * Bounded, access-ordered. Decoded bitmaps are the largest objects the app holds — a
     * 1600px image is roughly 10 MB as ARGB_8888 — so an unbounded cache is an OOM on a
     * mid-range phone after a handful of pictures.
     */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
    }
    private val missing = mutableSetOf<String>()

    data class Stored(val contentHash: String, val width: Int, val height: Int)

    fun fileFor(contentHash: String): File = File(dir, "$contentHash.webp")

    /**
     * Decodes, sanitises and stores. Suspending because decode plus WebP encode of a large
     * photo takes hundreds of milliseconds — running it on the caller's thread janks the
     * picker's return and can ANR on a big image.
     *
     * The decode-then-re-encode round trip is the sanitising step: EXIF, GPS tags, appended
     * payloads and polyglot-file tricks do not survive being rebuilt from a decoded bitmap.
     */
    suspend fun importImage(uri: Uri, maxEdge: Int = 1600, quality: Int = 82): Stored? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                // Software allocation is required: hardware bitmaps cannot be compressed.
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
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    decoded.compress(format, quality, out)
                    out.toByteArray()
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                fileFor(hash).writeBytes(bytes)
                val stored = Stored(hash, decoded.width, decoded.height)
                decoded.recycle()
                synchronized(cache) { cache.remove(hash); missing.remove(hash) }
                stored
            }.getOrNull()
        }

    /** Cheap enough to call from a draw pass: hits the cache, and remembers misses. */
    fun bitmapFor(contentHash: String): ImageBitmap? {
        if (contentHash.isBlank()) return null
        synchronized(cache) {
            cache[contentHash]?.let { return it }
            if (contentHash in missing) return null
        }
        val decoded = runCatching {
            val file = fileFor(contentHash)
            if (!file.exists()) null
            else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }.asImageBitmap()
        }.getOrNull()
        synchronized(cache) {
            if (decoded == null) missing.add(contentHash) else cache[contentHash] = decoded
        }
        return decoded
    }

    /**
     * Deletes stored images no page references any more. Without this the media directory
     * only ever grows, because deleting a block from a page never touched the file.
     */
    fun pruneUnreferenced(doc: ProfileDocument): Int {
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
        dir.listFiles()?.forEach { file ->
            val hash = file.nameWithoutExtension
            if (hash !in referenced && file.delete()) {
                removed++
                synchronized(cache) { cache.remove(hash); missing.remove(hash) }
            }
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
private const val HEADING_SP = 28f
private const val BODY_SP = 16f
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
            e.type == ElementType.Text && e.fontSize >= HEADING_SP - 2f -> QuickBlock.Heading(e.id, e.text)
            e.type == ElementType.Text -> QuickBlock.Body(e.id, e.text)
            e.type == ElementType.Media && e.mediaKind == MediaKind.Image ->
                QuickBlock.Picture(
                    e.id, e.mediaContentHash,
                    e.intrinsicWidth.toInt().coerceAtLeast(1),
                    e.intrinsicHeight.toInt().coerceAtLeast(1),
                    e.mediaDescription
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
                is QuickBlock.Picture -> {
                    val ratio = block.height.toFloat() / block.width.toFloat().coerceAtLeast(1f)
                    contentWidthDp * ratio.coerceIn(.2f, 2.5f)
                }
            }.toDouble()
        }.toFloat()
    return total > referenceWidthDp / MIN_PAGE_ASPECT
}

/** True when the page contains things the simple editor would silently drop. */
fun pageHasAdvancedContent(page: Page): Boolean = page.root.children.any { e ->
    e.children.isNotEmpty() ||
        e.type == ElementType.Stamp || e.type == ElementType.Widget ||
        e.type == ElementType.Link || e.type == ElementType.Button ||
        (e.type == ElementType.Media && e.mediaKind != MediaKind.Image)
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
            is QuickBlock.Picture -> {
                val ratio = block.height.toFloat() / block.width.toFloat().coerceAtLeast(1f)
                val image = contentWidthDp * ratio.coerceIn(.2f, 2.5f)
                val caption = if (block.caption.isBlank()) 0f
                else measureTextHeightDp(block.caption, CAPTION_SP, contentWidthDp, false, densityScale) + 4f
                image + caption
            }
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
        val rect = RectSpec(
            x = MARGIN_FRAC,
            y = cursor / totalDp,
            width = 1f - 2 * MARGIN_FRAC,
            height = h / totalDp,
            zIndex = index,
        )
        children += when (block) {
            is QuickBlock.Heading -> Element(
                type = ElementType.Text, id = block.id, name = "Heading",
                rect = rect, text = block.text, fontSize = HEADING_SP, bold = true,
                textArgb = readableTextArgb(page), textAlign = TextAlignMode.Left,
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
    val scroll = rememberScrollState()

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val referenceWidthDp = maxWidth.value

        // replaceDocument persists to the active slot, so every exit path here is durable.
        fun commit() {
            state.doc.profileName = profileName.trim().ifBlank { state.doc.profileName }
            applyBlocksToPage(page, blocks, referenceWidthDp, density)
            state.replaceDocument(state.doc)
            media.pruneUnreferenced(state.doc)
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
                    TextButton(onClick = { commit(); onBack() }) { Text("\u2190 Done") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { commit(); onOpenAdvanced() }) { Text("Advanced") }
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
                        Text("Preparing the image\u2026", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (showAdvancedWarning) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("This page uses the advanced editor", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "It has stamps, widgets or layered boxes. Saving here would replace them with a simple stack.",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onOpenAdvanced) { Text("Open advanced") }
                                TextButton(onClick = { showAdvancedWarning = false }) { Text("Simplify anyway") }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name") },
                    singleLine = true,
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

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { blocks = blocks + QuickBlock.Heading(makeId("head"), "Heading") }) { Text("+ Heading") }
                    OutlinedButton(onClick = { blocks = blocks + QuickBlock.Body(makeId("text"), "") }) { Text("+ Text") }
                    OutlinedButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) { Text("+ Image") }
                }

                if (blocks.isEmpty()) {
                    Text(
                        "This page is empty. Add a heading, some text, or a picture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                Text(
                    "Images are re-saved on this device before they are used, which removes location and camera information from the original file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Spacer(Modifier.height(40.dp))
            }
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
                        is QuickBlock.Heading -> "Heading"
                        is QuickBlock.Body -> "Text"
                        is QuickBlock.Picture -> "Image"
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
                            contentDescription = block.caption.ifBlank { "Image" },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        )
                    } else {
                        Text(
                            "Image data missing.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = block.caption,
                        onValueChange = { onChange(block.copy(caption = it.take(200))) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Caption (optional)") },
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
                Text("Pages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { renaming = true; draftName = pages.getOrElse(current) { "" } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Rename", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDeletePage, enabled = canDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
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
            title = { Text("Rename page") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(40) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onRenamePage(draftName.trim()); renaming = false }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
}
