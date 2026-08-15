package com.veilysocial.profiledesigner

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.*

enum class EditMode(@StringRes val labelRes: Int, @StringRes val shortRes: Int) {
    Background(R.string.mode_background, R.string.mode_background_short),
    Boxes(R.string.mode_boxes, R.string.mode_boxes_short),
    Foreground(R.string.mode_foreground, R.string.mode_foreground_short)
}

enum class EditorTool(val symbol: String, @StringRes val descriptionRes: Int, @StringRes val compactRes: Int) {
    Move("✥", R.string.tool_move, R.string.tool_move_short),
    Text("T", R.string.tool_text, R.string.tool_text_short),
    Block("◇", R.string.tool_block, R.string.tool_block_short),
    Link("↗", R.string.tool_link, R.string.tool_link_short),
    Button("▰", R.string.tool_button, R.string.tool_button_short),
    Stamp("★", R.string.tool_stamp, R.string.tool_stamp_short),
    Image("▧", R.string.tool_image, R.string.tool_image_short),
    Widget("⌘", R.string.tool_widget, R.string.tool_widget_short),
    Page("＋", R.string.tool_page, R.string.tool_page_short)
}

enum class PanelSection { Depth, MoveSize, Appearance, Content, FileActions }

data class HierarchyEntry(
    val label: String,
    val pageIndex: Int,
    val elementId: String,
    val depth: Int = 0,
    val type: ElementType? = null
)
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun child(q: RectSpec) = NRect(x + q.x * w, y + q.y * h, q.width * w, q.height * h)
    fun contains(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}
data class HitResult(val elementId: String, val parentId: String?, val rect: NRect, val parentRect: NRect)

data class PendingWidgetBoxResize(
    val widgetId: String,
    val boxId: String,
    val replaceTargetId: String?,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val currentWidth: Int,
    val currentHeight: Int
)

data class RenderStrings(val mediaKinds: List<String>, val mediaSuffix: String, val widgetSuffix: String, val widgetPaused: String)

private fun localizedDefaultProfile(context: Context): ProfileDocument {
    val d = makeDefaultProfile()
    d.profileName = context.getString(R.string.default_profile_name)
    val p = d.pages.firstOrNull() ?: return d
    p.name = context.getString(R.string.default_page_name)
    p.root.name = context.getString(R.string.default_page_editor_name)
    val root = p.root
    root.children.filter { it.type == ElementType.Stamp }.forEachIndexed { i, e ->
        e.name = context.getString(if (i == 0) R.string.default_star_name else R.string.default_star2_name)
    }
    root.children.firstOrNull { it.type == ElementType.Block }?.let { header ->
        header.name = context.getString(R.string.default_header_box)
        header.children.firstOrNull { it.type == ElementType.Text }?.let { title ->
            title.name = context.getString(R.string.default_profile_title_name)
            title.text = context.getString(R.string.default_profile_title)
        }
    }
    root.children.filter { it.type == ElementType.Block }.getOrNull(1)?.let { box ->
        box.name = context.getString(R.string.default_welcome_box)
        box.children.firstOrNull { it.type == ElementType.Text }?.let { body ->
            body.name = context.getString(R.string.default_welcome_text_name)
            body.text = context.getString(R.string.default_welcome_text)
        }
        box.children.firstOrNull { it.type == ElementType.Widget }?.let { widget ->
            widget.name = context.getString(R.string.default_widget_placeholder_name)
            widget.widgetLabel = context.getString(R.string.future_widget)
        }
    }
    return d
}

/** Fixed filename for the profile the app is actually using, distinct from named exports. */
const val ACTIVE_PROFILE_FILE = "active.txt"

/**
 * Restores the working profile, falling back to a fresh starter document when there is no
 * saved one or the saved one will not parse.
 */
fun loadActiveProfile(context: Context): ProfileDocument {
    val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    val file = File(dir, ACTIVE_PROFILE_FILE)
    if (!file.exists()) return localizedDefaultProfile(context)
    return runCatching { ProfileCodec.load(file) }.getOrElse {
        // Falling back to the default silently would destroy the evidence along with the work.
        // Keep the unreadable file so it can be inspected or recovered by hand.
        runCatching {
            file.copyTo(File(dir, "$ACTIVE_PROFILE_FILE.unreadable-${System.currentTimeMillis()}"), overwrite = true)
        }
        localizedDefaultProfile(context)
    }
}

class EditorState(private val context: Context) {
    /**
     * The working document is restored from disk on construction. Without this the app came
     * up on the built-in default every launch, so anything made in a previous session was
     * gone and Publish would overwrite a good published profile with the placeholder.
     */
    var doc by mutableStateOf(loadActiveProfile(context))
    var pageIndex by mutableIntStateOf(0)
    var selectedId by mutableStateOf(doc.pages.first().root.id)
    var panelCollapsed by mutableStateOf(false)
    var hierarchyCollapsed by mutableStateOf(true)
    var tool by mutableStateOf(EditorTool.Move)
    var mode by mutableStateOf(EditMode.Boxes)
    var focusedBoxId by mutableStateOf<String?>(firstBoxId())
    var selectedGradientStop by mutableIntStateOf(0)
    var currentFileName by mutableStateOf<String?>(null)
    var showSaveAs by mutableStateOf(false)
    var showOpen by mutableStateOf(false)
    var saveAsText by mutableStateOf(context.getString(R.string.default_profile_name))
    var inlineTextEditId by mutableStateOf<String?>(null)
    var inlineTextDraft by mutableStateOf("")
    var renderRevision by mutableIntStateOf(0); private set
    var uiRevision by mutableIntStateOf(0); private set
    var depthArmedId by mutableStateOf<String?>(null)
    var depthInvalidDrop by mutableStateOf(false)
    var depthRevision by mutableIntStateOf(0); private set
    private val expanded = mutableStateMapOf(
        PanelSection.Depth to false,
        PanelSection.MoveSize to false,
        PanelSection.Appearance to false,
        PanelSection.Content to false,
        PanelSection.FileActions to false
    )
    private val undo = ArrayDeque<ProfileDocument>()
    private val redo = ArrayDeque<ProfileDocument>()
    private var savedSnapshot = doc.deepCopy()
    private var continuousEditing = false
    private var depthEditing = false
    private var dragElement: Element? = null
    private var dragParentRect = NRect(0f, 0f, 1f, 1f)
    val profileDir: File = File(context.filesDir, "profiles").apply { mkdirs() }
    val widgetRepo = WidgetRepository(context)
    var showWidgetStudio by mutableStateOf(false)
    var showWidgetPicker by mutableStateOf(false)
    var widgetReplaceTargetId by mutableStateOf<String?>(null)
    var pendingWidgetBoxResize by mutableStateOf<PendingWidgetBoxResize?>(null)
    private val widgetProgramCache = mutableMapOf<String, Pair<String, WidgetProgram>>()

    fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
    fun toast(@StringRes id: Int, vararg args: Any) = Toast.makeText(context, text(id, *args), Toast.LENGTH_SHORT).show()

    fun ownProfileTextForPublish(): String = ProfileCodec.encodeText(doc)
    fun ownProfileNameForPublish(): String = doc.profileName

    /** Set when the working document could not be written. Surfaced in the UI, not swallowed. */
    var persistError by mutableStateOf<String?>(null)
        private set

    /**
     * Writes the working document to its fixed slot. Called at every commit point rather than
     * on a timer, so a process death between edits cannot lose more than the edit in progress.
     *
     * A failure here used to be swallowed, which meant an unencodable document silently never
     * reached disk and came back as the built-in default on the next launch. Silence is the
     * wrong default for the one function whose whole job is not losing the person's work.
     */
    fun persistActive(): Boolean {
        val outcome = runCatching {
            // Encode first. Writing a partial file over a good one is worse than not writing.
            val encoded = ProfileCodec.encodeText(doc)
            val target = File(profileDir, ACTIVE_PROFILE_FILE)
            val staging = File(profileDir, "$ACTIVE_PROFILE_FILE.tmp")
            staging.writeText(encoded, Charsets.UTF_8)
            if (target.exists()) target.delete()
            check(staging.renameTo(target)) { "could not replace the saved profile" }
        }
        persistError = outcome.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
        return outcome.isSuccess
    }

    fun clearPersistError() { persistError = null }

    /** Replaces the whole editable document. Used by first-run setup. */
    fun replaceDocument(next: ProfileDocument) {
        finishInlineTextEdit()
        doc = next
        pageIndex = 0
        selectedId = doc.pages.first().root.id
        focusedBoxId = firstBoxId()
        mode = EditMode.Boxes
        invalidate()
        persistActive()
    }
    fun sectionExpanded(s: PanelSection) = expanded[s] == true
    fun toggleSection(s: PanelSection) { expanded[s] = !(expanded[s] ?: false) }

    private fun firstBoxId(): String? = doc.pages.getOrNull(pageIndex)?.root?.children?.firstOrNull { it.type == ElementType.Block }?.id
    private fun invalidate(redrawOnly: Boolean = false) { renderRevision++; if (!redrawOnly) uiRevision++ }

    fun selected(): Element? = findElement(selectedId)
    fun findElement(id: String?): Element? {
        if (id == null) return null
        val root = doc.pages.getOrNull(pageIndex)?.root ?: return null
        fun find(e: Element): Element? { if (e.id == id) return e; e.children.forEach { find(it)?.let { f -> return f } }; return null }
        return find(root)
    }
    fun parentOf(id: String): Element? {
        val root = doc.pages.getOrNull(pageIndex)?.root ?: return null
        fun walk(e: Element): Element? { e.children.forEach { c -> if (c.id == id) return e else walk(c)?.let { return it } }; return null }
        return walk(root)
    }
    fun isPageRoot(e: Element?) = e != null && doc.pages.getOrNull(pageIndex)?.root?.id == e.id
    fun isRootBox(e: Element?): Boolean = e != null && e.type == ElementType.Block && parentOf(e.id)?.id == doc.pages[pageIndex].root.id

    fun displayName(e: Element): String {
        if (isPageRoot(e)) return text(R.string.page_prefix, doc.pages.getOrNull(pageIndex)?.name ?: e.name)
        return when (e.type) {
        ElementType.Text -> text(R.string.item_text_display, e.text.ifBlank { text(R.string.tool_text_short) }.take(28))
        ElementType.Button -> text(R.string.item_button_display, e.label.ifBlank { text(R.string.tool_button_short) }.take(28))
        ElementType.Link -> text(R.string.item_link_display, e.label.ifBlank { text(R.string.tool_link_short) }.take(28))
        ElementType.Block -> text(R.string.item_box_display, e.name.ifBlank { text(R.string.tool_block_short) }.take(28))
        ElementType.Stamp -> text(R.string.item_stamp_display, e.stampDecoration.builtinName.ifBlank { text(R.string.tool_stamp_short) }.take(28))
        ElementType.Media -> text(R.string.item_media_display, e.mediaTitle.ifBlank { text(R.string.tool_image_short) }.take(28))
        ElementType.Widget -> text(R.string.item_widget_display, e.widgetLabel.ifBlank { text(R.string.tool_widget_short) }.take(28))
        }
    }

    private fun elementOrdinal(target: Element): Int {
        var ordinal = 0
        var result = 1
        val root = doc.pages.getOrNull(pageIndex)?.root ?: return 1
        fun walk(e: Element) {
            if (e.type == target.type) ordinal++
            if (e.id == target.id) { result = ordinal.coerceAtLeast(1); return }
            e.children.forEach(::walk)
        }
        walk(root)
        return result
    }

    private fun nameSlug(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "Item" }
        .take(28)

    fun refreshAutoName(e: Element) {
        val source = when (e.type) {
            ElementType.Text -> e.text
            ElementType.Button, ElementType.Link -> e.label
            ElementType.Media -> e.mediaTitle
            ElementType.Widget -> e.widgetLabel
            ElementType.Stamp -> e.stampDecoration.builtinName
            ElementType.Block -> return
        }
        e.name = "${e.type.name}${elementOrdinal(e)}_${nameSlug(source)}"
    }

    fun beginInlineTextEdit(id: String) {
        val e = findElement(id) ?: return
        if (mode != EditMode.Foreground || e.type != ElementType.Text) return
        if (inlineTextEditId != id) pushUndo()
        selectedId = id
        inlineTextEditId = id
        inlineTextDraft = e.text
        uiRevision++
    }

    fun updateInlineText(value: String) {
        val id = inlineTextEditId ?: return
        val e = findElement(id) ?: return
        inlineTextDraft = value
        e.text = value
        refreshAutoName(e)
        renderRevision++
    }

    fun finishInlineTextEdit() {
        if (inlineTextEditId == null) return
        inlineTextEditId = null
        inlineTextDraft = ""
        uiRevision++
    }

    fun longPressAt(nxPage: Float, nyPage: Float) {
        val hit = hitTest(nxPage, nyPage)
        val e = findElement(hit.elementId)
        selectedId = hit.elementId
        if (mode == EditMode.Foreground && e?.type == ElementType.Text) beginInlineTextEdit(e.id)
        else uiRevision++
    }

    private fun pushUndo() { undo.addLast(doc.deepCopy()); while (undo.size > 60) undo.removeFirst(); redo.clear() }
    fun editSelected(push: Boolean = true, block: (Element) -> Unit) { if (push) pushUndo(); selected()?.let(block); invalidate() }
    fun editDoc(push: Boolean = true, block: (ProfileDocument) -> Unit) { if (push) pushUndo(); block(doc); invalidate() }
    fun continuousEdit(block: (Element) -> Unit) { if (!continuousEditing) { pushUndo(); continuousEditing = true }; selected()?.let(block); invalidate(true) }
    fun continuousEditDoc(block: (ProfileDocument) -> Unit) { if (!continuousEditing) { pushUndo(); continuousEditing = true }; block(doc); invalidate(true) }
    fun endContinuous() { continuousEditing = false; uiRevision++ }

    fun undo() { if (undo.isEmpty()) return; redo.addLast(doc.deepCopy()); doc = undo.removeLast(); repairSelection() }
    fun redo() { if (redo.isEmpty()) return; undo.addLast(doc.deepCopy()); doc = redo.removeLast(); repairSelection() }
    fun revert() { pushUndo(); doc = savedSnapshot.deepCopy(); pageIndex = 0; selectedId = doc.pages[0].root.id; mode = EditMode.Boxes; focusedBoxId = firstBoxId(); invalidate() }
    private fun repairSelection() { inlineTextEditId = null; inlineTextDraft = ""; pageIndex = pageIndex.coerceIn(doc.pages.indices); focusedBoxId = firstBoxId(); selectedId = doc.pages[pageIndex].root.id; mode = EditMode.Boxes; invalidate() }

    fun changeEditMode(next: EditMode) {
        finishInlineTextEdit()
        when (next) {
            EditMode.Background -> { mode = next; selectedId = doc.pages[pageIndex].root.id; depthArmedId = null }
            EditMode.Boxes -> { mode = next; selectedId = focusedBoxId ?: firstBoxId() ?: doc.pages[pageIndex].root.id; depthArmedId = null }
            EditMode.Foreground -> {
                val candidate = when { isRootBox(selected()) -> selectedId; findElement(focusedBoxId)?.type == ElementType.Block -> focusedBoxId; else -> firstBoxId() }
                if (candidate == null) { mode = EditMode.Boxes; return }
                enterBox(candidate)
            }
        }
        invalidate()
    }
    fun enterBox(id: String) {
        finishInlineTextEdit()
        val e = findElement(id) ?: return
        if (!isRootBox(e)) return
        focusedBoxId = id; selectedId = id; mode = EditMode.Foreground; depthArmedId = null; invalidate()
    }
    fun exitBox() { finishInlineTextEdit(); mode = EditMode.Boxes; selectedId = focusedBoxId ?: firstBoxId() ?: doc.pages[pageIndex].root.id; depthArmedId = null; invalidate() }

    fun hierarchy(): List<HierarchyEntry> {
        val out = mutableListOf<HierarchyEntry>()
        doc.pages.forEachIndexed { pi, p ->
            fun walk(e: Element, depth: Int) {
                val prefix = if (depth == 0) text(R.string.page_prefix, p.name) else "  ".repeat(depth) + displayName(e)
                out += HierarchyEntry(prefix, pi, e.id); e.children.forEach { walk(it, depth + 1) }
            }
            walk(p.root, 0)
        }
        return out
    }
    fun hierarchyForPage(pi: Int): List<HierarchyEntry> {
        val page = doc.pages.getOrNull(pi) ?: return emptyList()
        val out = mutableListOf<HierarchyEntry>()
        fun walk(e: Element, depth: Int) {
            out += HierarchyEntry(displayName(e), pi, e.id, depth, e.type)
            e.children.forEach { walk(it, depth + 1) }
        }
        page.root.children.forEach { walk(it, 0) }
        return out
    }
    fun select(entry: HierarchyEntry) {
        pageIndex = entry.pageIndex; selectedGradientStop = 0
        val root = doc.pages[pageIndex].root
        val e = findElement(entry.elementId) ?: root
        if (e.id == root.id) {
            mode = EditMode.Background; focusedBoxId = firstBoxId(); selectedId = root.id
        } else {
            val directParent = parentOf(e.id)
            if (directParent?.id == root.id && e.type == ElementType.Stamp) {
                mode = EditMode.Background; selectedId = e.id
            } else if (directParent?.id == root.id) {
                mode = EditMode.Boxes; selectedId = e.id; if (e.type == ElementType.Block) focusedBoxId = e.id
            } else {
                var top = e
                var p = parentOf(top.id)
                while (p != null && p.id != root.id) { top = p; p = parentOf(top.id) }
                if (top.type == ElementType.Block) { focusedBoxId = top.id; mode = EditMode.Foreground; selectedId = e.id }
                else { mode = EditMode.Boxes; selectedId = e.id }
            }
        }
        depthArmedId = null; invalidate()
    }

    fun depthScope(): MutableList<Element> {
        val root = doc.pages[pageIndex].root
        return when (mode) {
            EditMode.Background -> root.children.filter { it.type == ElementType.Stamp }.toMutableList()
            EditMode.Boxes -> root.children.filter { it.type != ElementType.Stamp }.toMutableList()
            EditMode.Foreground -> findElement(focusedBoxId)?.children ?: mutableListOf()
        }
    }
    fun depthTopFirst(): List<Element> = depthScope().sortedByDescending { it.rect.zIndex }
    fun armDepth(id: String) { depthArmedId = if (depthArmedId == id) null else id; depthInvalidDrop = false }
    fun startDepthDrag(id: String) { depthArmedId = id; depthInvalidDrop = false; selectedId = id; uiRevision++ }
    fun shiftDepth(id: String, deltaIndex: Int) {
        val v = depthTopFirst().toMutableList(); val from = v.indexOfFirst { it.id == id }; if (from < 0) return
        val to = (from + deltaIndex).coerceIn(v.indices); if (to == from) return
        if (!depthEditing) { pushUndo(); depthEditing = true }
        val item = v.removeAt(from); v.add(to, item)
        v.forEachIndexed { i, e -> e.rect.zIndex = v.size - 1 - i }
        selectedId = id; renderRevision++; depthRevision++
    }
    fun finishDepthDrag() { depthEditing = false; depthInvalidDrop = false; depthArmedId = null; uiRevision++ }

    private fun reIdTree(e: Element) { e.id = makeId("item"); e.children.forEach(::reIdTree) }
    fun deleteSelected() {
        val page = doc.pages[pageIndex]
        if (selectedId == page.root.id) {
            if (doc.pages.size <= 1) { toast(R.string.must_keep_page); return }
            pushUndo(); val removed = page.id; doc.pages.removeAt(pageIndex); if (doc.defaultPageId == removed) doc.defaultPageId = doc.pages.first().id
            pageIndex = pageIndex.coerceAtMost(doc.pages.lastIndex); selectedId = doc.pages[pageIndex].root.id; focusedBoxId = firstBoxId(); invalidate(); return
        }
        val parent = parentOf(selectedId) ?: return; pushUndo(); parent.children.removeAll { it.id == selectedId }
        if (selectedId == focusedBoxId) focusedBoxId = firstBoxId()
        selectedId = if (mode == EditMode.Foreground) focusedBoxId ?: page.root.id else parent.id; invalidate()
    }
    fun duplicateSelected() {
        val page = doc.pages[pageIndex]
        if (selectedId == page.root.id) {
            if (doc.pages.size >= VspfLimits.MAX_PAGES) return
            pushUndo(); val copy = page.copy(id = makeId("page"), name = page.name + text(R.string.copy_suffix), root = page.root.deepCopy()); reIdTree(copy.root); copy.root.name = copy.name
            doc.pages += copy; pageIndex = doc.pages.lastIndex; selectedId = copy.root.id; focusedBoxId = firstBoxId(); invalidate(); return
        }
        val e = selected() ?: return; val parent = parentOf(selectedId) ?: return; pushUndo(); val c = e.deepCopy(); reIdTree(c); c.name += text(R.string.copy_suffix)
        c.rect.x = (c.rect.x + .03f).coerceAtMost(1f - c.rect.width); c.rect.y = (c.rect.y + .03f).coerceAtMost(1f - c.rect.height); c.rect.zIndex = e.rect.zIndex + 1
        parent.children += c; selectedId = c.id; if (isRootBox(c)) focusedBoxId = c.id; invalidate()
    }
    fun adjustLayer(delta: Int) { val e = selected() ?: return; if (isPageRoot(e)) return; pushUndo(); e.rect.zIndex += delta; invalidate() }

    fun allowedTools(): List<EditorTool> = when (mode) {
        EditMode.Background -> listOf(EditorTool.Move, EditorTool.Stamp, EditorTool.Page)
        EditMode.Boxes -> listOf(EditorTool.Move, EditorTool.Block, EditorTool.Page)
        EditMode.Foreground -> listOf(EditorTool.Move, EditorTool.Text, EditorTool.Link, EditorTool.Button, EditorTool.Stamp, EditorTool.Image, EditorTool.Widget)
    }
    fun add(tool: EditorTool) {
        if (tool !in allowedTools()) return
        if (tool == EditorTool.Move) { this.tool = tool; return }
        if (tool == EditorTool.Page) {
            editDoc { d -> val p = Page(name = text(R.string.new_page)); p.root.name = p.name; p.root.background.solidArgb = 0xFFF7F7F7.toInt(); d.pages += p; pageIndex = d.pages.lastIndex; selectedId = p.root.id; focusedBoxId = null; mode = EditMode.Boxes }
            this.tool = EditorTool.Move; return
        }
        if (tool == EditorTool.Widget) { widgetReplaceTargetId = null; showWidgetPicker = true; this.tool = EditorTool.Move; return }
        pushUndo(); val root = doc.pages[pageIndex].root
        val parent = when (mode) {
            EditMode.Background -> root
            EditMode.Boxes -> root
            EditMode.Foreground -> {
                val focus = findElement(focusedBoxId) ?: return
                val s = selected(); if (s?.type == ElementType.Block && s.id != root.id && isDescendantOf(s.id, focus.id)) s else focus
            }
        }
        val nextZ = (parent.children.maxOfOrNull { it.rect.zIndex } ?: 0) + 1
        val e = when (tool) {
            EditorTool.Text -> Element(type = ElementType.Text, name = text(R.string.new_text_name), text = text(R.string.new_text_content), rect = RectSpec(.12f, .12f, .55f, .14f, nextZ))
            EditorTool.Block -> Element(type = ElementType.Block, name = text(R.string.new_box), rect = RectSpec(.12f, .12f, .52f, .30f, nextZ), background = BackgroundSpec(solidArgb = 0xDDFFFFFF.toInt()))
            EditorTool.Link -> Element(type = ElementType.Link, name = text(R.string.new_link), label = text(R.string.new_page_link), target = doc.pages[pageIndex].id, rect = RectSpec(.12f, .12f, .42f, .10f, nextZ))
            EditorTool.Button -> Element(type = ElementType.Button, name = text(R.string.new_button), label = text(R.string.new_button), rect = RectSpec(.12f, .12f, .34f, .11f, nextZ), background = BackgroundSpec(solidArgb = 0xFFECF0F8.toInt()), fontSize = 16f, textAlign = TextAlignMode.Center)
            EditorTool.Stamp -> Element(type = ElementType.Stamp, name = text(R.string.new_stamp), rect = RectSpec(.12f, .12f, .10f, .10f, nextZ), stampDecoration = DecorationRef(builtinName = "Star1", basedOnBuiltin = "Star1"))
            EditorTool.Image -> Element(type = ElementType.Media, name = text(R.string.new_image), rect = RectSpec(.12f, .12f, .48f, .28f, nextZ), mediaKind = MediaKind.Image, mediaTitle = text(R.string.image_placeholder))
            EditorTool.Widget -> Element(type = ElementType.Widget, name = text(R.string.new_widget), rect = RectSpec(.12f, .12f, .48f, .24f, nextZ), widgetLabel = text(R.string.future_widget))
            else -> return
        }
        parent.children += e; refreshAutoName(e); selectedId = e.id; if (mode == EditMode.Boxes && e.type == ElementType.Block) focusedBoxId = e.id; invalidate(); this.tool = EditorTool.Move
    }
    private fun isDescendantOf(id: String, ancestorId: String): Boolean { var p = parentOf(id); while (p != null) { if (p.id == ancestorId) return true; p = parentOf(p.id) }; return false }

    fun globalRectFor(id: String?): NRect? {
        val root = doc.pages.getOrNull(pageIndex)?.root ?: return null
        if (id == root.id) return NRect(0f, 0f, 1f, 1f)
        var found: NRect? = null
        fun walk(e: Element, parent: NRect) { if (found != null) return; val r = parent.child(e.rect); if (e.id == id) { found = r; return }; e.children.forEach { walk(it, r) } }
        root.children.forEach { walk(it, NRect(0f, 0f, 1f, 1f)) }; return found
    }
    fun cameraTarget(): NRect {
        if (mode != EditMode.Foreground) return NRect(0f, 0f, 1f, 1f)
        val r = globalRectFor(focusedBoxId) ?: return NRect(0f, 0f, 1f, 1f)
        val mx = r.w * .06f; val my = r.h * .06f
        val left = (r.x - mx).coerceAtLeast(0f); val top = (r.y - my).coerceAtLeast(0f)
        val right = (r.x + r.w + mx).coerceAtMost(1f); val bottom = (r.y + r.h + my).coerceAtMost(1f)
        return NRect(left, top, (right-left).coerceAtLeast(.02f), (bottom-top).coerceAtLeast(.02f))
    }

    fun hitTest(nxPage: Float, nyPage: Float): HitResult {
        val root = doc.pages[pageIndex].root
        return when (mode) {
            EditMode.Background -> {
                val candidates = root.children.filter { it.type == ElementType.Stamp }.sortedByDescending { it.rect.zIndex }
                candidates.firstNotNullOfOrNull { e -> val r=NRect(0f,0f,1f,1f).child(e.rect); if(r.contains(nxPage,nyPage)) HitResult(e.id,root.id,r,NRect(0f,0f,1f,1f)) else null }
                    ?: HitResult(root.id,null,NRect(0f,0f,1f,1f),NRect(0f,0f,1f,1f))
            }
            EditMode.Boxes -> {
                val candidates = root.children.filter { it.type != ElementType.Stamp }.sortedByDescending { it.rect.zIndex }
                candidates.firstNotNullOfOrNull { e -> val r=NRect(0f,0f,1f,1f).child(e.rect); if(r.contains(nxPage,nyPage)) HitResult(e.id,root.id,r,NRect(0f,0f,1f,1f)) else null }
                    ?: HitResult(root.id,null,NRect(0f,0f,1f,1f),NRect(0f,0f,1f,1f))
            }
            EditMode.Foreground -> {
                val focus = findElement(focusedBoxId) ?: return HitResult(root.id,null,NRect(0f,0f,1f,1f),NRect(0f,0f,1f,1f))
                val focusRect = globalRectFor(focus.id) ?: NRect(0f,0f,1f,1f)
                val hits = mutableListOf<Pair<Int,HitResult>>()
                fun walk(e: Element, parentRect: NRect, parentId: String) { val r=parentRect.child(e.rect); if(e.rect.visible && r.contains(nxPage,nyPage)) hits += e.rect.zIndex to HitResult(e.id,parentId,r,parentRect); e.children.forEach { walk(it,r,e.id) } }
                focus.children.forEach { walk(it,focusRect,focus.id) }
                hits.maxByOrNull { it.first }?.second ?: HitResult(focus.id,root.id,focusRect,NRect(0f,0f,1f,1f))
            }
        }
    }

    fun tapAt(nxPage: Float, nyPage: Float, doubleTap: Boolean = false) {
        val hit = hitTest(nxPage, nyPage)
        val hitElement = findElement(hit.elementId)
        if (inlineTextEditId != null && inlineTextEditId != hit.elementId) finishInlineTextEdit()
        selectedId = hit.elementId
        if (mode == EditMode.Foreground && doubleTap && hitElement?.type == ElementType.Text) {
            beginInlineTextEdit(hitElement.id)
            return
        }
        if (mode == EditMode.Boxes && hitElement?.type == ElementType.Block) {
            focusedBoxId = hit.elementId; if (doubleTap) enterBox(hit.elementId)
        }
        uiRevision++
    }
    fun beginDrag(nxPage: Float, nyPage: Float) {
        val hit = hitTest(nxPage,nyPage); selectedId=hit.elementId; dragParentRect=hit.parentRect
        val e=selected(); dragElement=when(mode){
            EditMode.Background -> if(e?.type==ElementType.Stamp)e else null
            EditMode.Boxes -> if(e!=null && e.id!=doc.pages[pageIndex].root.id)e else null
            EditMode.Foreground -> if(e!=null && e.id!=focusedBoxId)e else null
        }
        if(dragElement!=null)pushUndo()
    }
    fun dragBy(dxNormPage: Float,dyNormPage: Float){val e=dragElement?:return;val pw=dragParentRect.w.coerceAtLeast(.001f);val ph=dragParentRect.h.coerceAtLeast(.001f);e.rect.x=(e.rect.x+dxNormPage/pw).coerceIn(0f,1f-e.rect.width);e.rect.y=(e.rect.y+dyNormPage/ph).coerceIn(0f,1f-e.rect.height);renderRevision++}
    fun endDrag(){dragElement=null;uiRevision++}

    private fun widgetLocalId(recordKey: String): String? = recordKey.removePrefix("local-widget:").takeIf { recordKey.startsWith("local-widget:") && it.isNotBlank() }
    fun widgetPackageFor(e: Element): WidgetPackage? = if (e.type == ElementType.Widget) widgetLocalId(e.widgetRecordKey)?.let(widgetRepo::find) else null
    fun widgetProgramFor(e: Element): WidgetProgram? {
        val localId = widgetLocalId(e.widgetRecordKey) ?: return null
        val expected = e.widgetSourceHash
        val cached = widgetProgramCache[localId]
        if (cached != null && expected.isNotBlank() && cached.first == expected) return cached.second
        val pkg = widgetRepo.find(localId) ?: return null
        if (expected.isNotBlank() && pkg.manifest.sourceHash != expected) return null
        val program = runCatching { VeilWidgetBytecode.decode(pkg.bytecode) }.getOrNull() ?: return null
        widgetProgramCache[localId] = pkg.manifest.sourceHash to program
        return program
    }
    private fun foregroundParent(): Element? {
        val root = doc.pages.getOrNull(pageIndex)?.root ?: return null
        if (mode != EditMode.Foreground) return null
        val focus = findElement(focusedBoxId) ?: return null
        val s = selected()
        return if (s?.type == ElementType.Block && s.id != root.id && isDescendantOf(s.id, focus.id)) s else focus
    }
    fun placeWidget(pkg: WidgetPackage) {
        val replaceTarget = findElement(widgetReplaceTargetId)?.takeIf { it.type == ElementType.Widget }
        val parent = if (replaceTarget != null) parentOf(replaceTarget.id) else foregroundParent()
        if (parent == null) return
        val page = doc.pages[pageIndex]
        val parentGlobal = globalRectFor(parent.id) ?: return
        val logicalPageWidth = 600f
        val logicalPageHeight = logicalPageWidth / page.aspectRatio
        val availableW = (logicalPageWidth * parentGlobal.w).roundToInt().coerceAtLeast(1)
        val availableH = (logicalPageHeight * parentGlobal.h).roundToInt().coerceAtLeast(1)
        val dw = pkg.manifest.defaultWidth
        val dh = pkg.manifest.defaultHeight

        if (dw > availableW || dh > availableH) {
            // The page canvas itself is the hard ceiling. Widgets may recommend a size,
            // but neither widget code nor the runtime is allowed to resize the page.
            if (isPageRoot(parent)) {
                toast(R.string.widget_cannot_fit_canvas, pkg.manifest.name, dw, dh, availableW, availableH)
                return
            }

            // A normal box may be enlarged by the *page editor* after explicit user
            // confirmation. This is deliberately not a widget/runtime capability.
            val boxContainer = parentOf(parent.id) ?: page.root
            val containerGlobal = globalRectFor(boxContainer.id) ?: NRect(0f, 0f, 1f, 1f)
            val containerW = (logicalPageWidth * containerGlobal.w).roundToInt().coerceAtLeast(1)
            val containerH = (logicalPageHeight * containerGlobal.h).roundToInt().coerceAtLeast(1)
            if (dw > containerW || dh > containerH) {
                toast(R.string.widget_cannot_fit_canvas, pkg.manifest.name, dw, dh, containerW, containerH)
                return
            }

            pendingWidgetBoxResize = PendingWidgetBoxResize(
                widgetId = pkg.manifest.widgetId,
                boxId = parent.id,
                replaceTargetId = replaceTarget?.id,
                requestedWidth = dw,
                requestedHeight = dh,
                currentWidth = availableW,
                currentHeight = availableH
            )
            showWidgetPicker = false
            return
        }

        commitWidgetPlacement(pkg, parent, replaceTarget, pushUndoSnapshot = true, fitToCurrentBox = false)
    }

    private fun commitWidgetPlacement(pkg: WidgetPackage, parent: Element, replaceTarget: Element?, pushUndoSnapshot: Boolean, fitToCurrentBox: Boolean) {
        val page = doc.pages[pageIndex]
        val parentGlobal = globalRectFor(parent.id) ?: return
        val logicalPageWidth = 600f
        val logicalPageHeight = logicalPageWidth / page.aspectRatio
        val availableW = (logicalPageWidth * parentGlobal.w).roundToInt().coerceAtLeast(1)
        val availableH = (logicalPageHeight * parentGlobal.h).roundToInt().coerceAtLeast(1)
        val dw = pkg.manifest.defaultWidth
        val dh = pkg.manifest.defaultHeight
        val oversize = dw > availableW || dh > availableH
        if (oversize && !fitToCurrentBox) {
            toast(R.string.widget_cannot_fit, pkg.manifest.name, dw, dh, availableW, availableH)
            return
        }
        if (pushUndoSnapshot) pushUndo()
        // If the page author explicitly chooses to keep the current box, scale the
        // widget uniformly to fit. The manifest's recommended/default size remains
        // stored unchanged, so the inspector can still warn that it is being shown
        // at a non-recommended size. Widget code itself never controls this scale.
        val fitScale = if (fitToCurrentBox) minOf(1f, availableW / dw.toFloat(), availableH / dh.toFloat()) else 1f
        val placedW = dw * fitScale
        val placedH = dh * fitScale
        val w = (placedW / availableW.toFloat()).coerceIn(.05f, 1f)
        val h = (placedH / availableH.toFloat()).coerceIn(.05f, 1f)
        val e = if (replaceTarget != null) {
            replaceTarget.rect.width = w; replaceTarget.rect.height = h
            replaceTarget.rect.x = replaceTarget.rect.x.coerceAtMost(1f-w); replaceTarget.rect.y = replaceTarget.rect.y.coerceAtMost(1f-h)
            replaceTarget
        } else {
            val nextZ = (parent.children.maxOfOrNull { it.rect.zIndex } ?: 0) + 1
            Element(type=ElementType.Widget,name=pkg.manifest.name,rect=RectSpec(((1f-w)/2f).coerceAtLeast(0f),((1f-h)/2f).coerceAtLeast(0f),w,h,nextZ)).also { parent.children += it }
        }
        e.name = pkg.manifest.name
        e.widgetLabel = pkg.manifest.name
        e.widgetRecordKey = "local-widget:${pkg.manifest.widgetId}"
        e.widgetSourceHash = pkg.manifest.sourceHash
        e.widgetDefaultWidth = dw.toLong(); e.widgetDefaultHeight = dh.toLong(); e.widgetWarnOnResize = pkg.manifest.warnOnResize
        selectedId = e.id
        refreshAutoName(e)
        runCatching { VeilWidgetBytecode.decode(pkg.bytecode) }.getOrNull()?.let { widgetProgramCache[pkg.manifest.widgetId] = pkg.manifest.sourceHash to it }
        widgetReplaceTargetId = null
        pendingWidgetBoxResize = null
        showWidgetPicker = false
        invalidate()
    }

    fun confirmWidgetBoxResize() {
        val pending = pendingWidgetBoxResize ?: return
        val pkg = widgetRepo.find(pending.widgetId) ?: run { pendingWidgetBoxResize = null; return }
        val box = findElement(pending.boxId)?.takeIf { it.type == ElementType.Block } ?: run { pendingWidgetBoxResize = null; return }
        val container = parentOf(box.id) ?: doc.pages[pageIndex].root
        val page = doc.pages[pageIndex]
        val containerGlobal = globalRectFor(container.id) ?: NRect(0f, 0f, 1f, 1f)
        val logicalPageWidth = 600f
        val logicalPageHeight = logicalPageWidth / page.aspectRatio
        val containerW = (logicalPageWidth * containerGlobal.w).coerceAtLeast(1f)
        val containerH = (logicalPageHeight * containerGlobal.h).coerceAtLeast(1f)
        val neededW = (pending.requestedWidth / containerW).coerceIn(.02f, 1f)
        val neededH = (pending.requestedHeight / containerH).coerceIn(.02f, 1f)

        pushUndo()
        box.rect.width = max(box.rect.width, neededW)
        box.rect.height = max(box.rect.height, neededH)
        // If expansion would cross the parent's edge, slide the box just enough to fit.
        box.rect.x = box.rect.x.coerceAtMost((1f - box.rect.width).coerceAtLeast(0f))
        box.rect.y = box.rect.y.coerceAtMost((1f - box.rect.height).coerceAtLeast(0f))

        val replace = findElement(pending.replaceTargetId)?.takeIf { it.type == ElementType.Widget }
        pendingWidgetBoxResize = null
        commitWidgetPlacement(pkg, box, replace, pushUndoSnapshot = false, fitToCurrentBox = false)
    }

    fun keepWidgetBoxSize() {
        val pending = pendingWidgetBoxResize ?: return
        val pkg = widgetRepo.find(pending.widgetId) ?: run { pendingWidgetBoxResize = null; return }
        val box = findElement(pending.boxId)?.takeIf { it.type == ElementType.Block } ?: run { pendingWidgetBoxResize = null; return }
        val replace = findElement(pending.replaceTargetId)?.takeIf { it.type == ElementType.Widget }
        pendingWidgetBoxResize = null
        commitWidgetPlacement(pkg, box, replace, pushUndoSnapshot = true, fitToCurrentBox = true)
    }

    fun cancelWidgetBoxResize() {
        pendingWidgetBoxResize = null
        widgetReplaceTargetId = null
    }

    fun placeBlankWidget() {
        pushUndo()
        val parent = foregroundParent() ?: return
        val nextZ = (parent.children.maxOfOrNull { it.rect.zIndex } ?: 0) + 1
        val e = Element(type = ElementType.Widget, name = text(R.string.new_widget), rect = RectSpec(.12f,.12f,.48f,.24f,nextZ), widgetLabel = text(R.string.future_widget))
        parent.children += e; selectedId=e.id; refreshAutoName(e); showWidgetPicker=false; invalidate()
    }
    fun acceptCurrentWidgetVersion(e: Element) {
        val pkg=widgetPackageFor(e)?:return
        pushUndo(); e.widgetSourceHash=pkg.manifest.sourceHash; e.widgetDefaultWidth=pkg.manifest.defaultWidth.toLong(); e.widgetDefaultHeight=pkg.manifest.defaultHeight.toLong(); e.widgetWarnOnResize=pkg.manifest.warnOnResize; invalidate()
    }
    fun chooseReplacementFor(e: Element) { widgetReplaceTargetId=e.id; showWidgetPicker=true }

    fun widgetLogicalSize(e: Element): Pair<Int, Int>? {
        if (e.type != ElementType.Widget) return null
        val parent = parentOf(e.id) ?: return null
        val page = doc.pages[pageIndex]
        val parentGlobal = globalRectFor(parent.id) ?: return null
        val pageW=600f; val pageH=pageW/page.aspectRatio
        return ((pageW*parentGlobal.w*e.rect.width).roundToInt()) to ((pageH*parentGlobal.h*e.rect.height).roundToInt())
    }

    private fun safeName(s:String)=s.trim().replace(Regex("[^A-Za-z0-9._ -]"),"_").ifBlank{"profile"}.take(80)
    fun save(){val name=currentFileName?:"${safeName(doc.profileName)}.txt";try{ProfileCodec.save(doc,File(profileDir,name));currentFileName=name;savedSnapshot=doc.deepCopy();toast(R.string.saved_locally)}catch(e:Exception){toast(R.string.save_failed,e.message?:"")}}
    fun saveAs(name:String){val fileName=if(name.endsWith(".txt",true))safeName(name)else safeName(name)+".txt";currentFileName=fileName;save();showSaveAs=false}
    fun localProfiles():List<File> = profileDir.listFiles{f->f.extension.equals("txt",true)&&f.name!="live_profile.txt"}?.sortedBy{it.name.lowercase(Locale.ROOT)}?:emptyList()
    fun open(file:File){try{doc=ProfileCodec.load(file);currentFileName=file.name;savedSnapshot=doc.deepCopy();undo.clear();redo.clear();pageIndex=0;selectedId=doc.pages[0].root.id;focusedBoxId=firstBoxId();mode=EditMode.Boxes;showOpen=false;toast(R.string.opened_profile,file.nameWithoutExtension)}catch(e:Exception){toast(R.string.open_failed,e.message?:"")}}
    fun publishLocal(){try{ProfileCodec.save(doc,File(profileDir,"live_profile.txt"));toast(R.string.published_local)}catch(e:Exception){toast(R.string.publish_failed,e.message?:"")}}
}

internal val DesignerColorScheme = lightColorScheme(
    primary = Color(0xFFB24F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE5E8),
    onPrimaryContainer = Color(0xFF64262F),
    secondary = Color(0xFF6E8194),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F5FA),
    onSecondaryContainer = Color(0xFF35495C),
    tertiary = Color(0xFFC77872),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFEFFFF),
    surfaceVariant = Color(0xFFF2F6FA),
    outline = Color(0xFF8795A3),
    outlineVariant = Color(0xFFDCE5ED),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC)
)

@Composable
fun EditorScreen(state: EditorState, onBack: () -> Unit) {
    BackHandler(enabled = true) {
        when {
            state.showWidgetStudio -> state.showWidgetStudio = false
            else -> { state.persistActive(); onBack() }
        }
    }
    if (state.showWidgetStudio) {
        Box(Modifier.fillMaxSize()) {
            WidgetStudioScreen(repo = state.widgetRepo, onBack = { state.showWidgetStudio = false })
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Captured here on purpose: BoxScope and BoxWithConstraintsScope share the
            // @LayoutScopeMarker DslMarker, so maxWidth is unreachable from inside the
            // nested Box below.
            val availableWidth: Dp = maxWidth
            val wide = availableWidth >= 720.dp
            val panelWidth: Dp = minOf(370.dp, availableWidth * .42f)
            Column(Modifier.fillMaxSize()) {
                EditorHeader(state, onBack)
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    PageWorkspace(state, Modifier.fillMaxSize())
                    if (!state.panelCollapsed) {
                        PropertiesPanel(
                            state,
                            Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomEnd)
                                .width(if (wide) panelWidth else availableWidth)
                                .then(if (wide) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(.55f))
                        )
                    } else {
                        Surface(
                            tonalElevation = 3.dp, shadowElevation = 3.dp,
                            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                            modifier = Modifier.align(Alignment.TopEnd).width(44.dp).height(58.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { state.panelCollapsed = false }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                                    Text("\u2039", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
                ToolStrip(state, Modifier.fillMaxWidth())
            }
        }
    }
    if (state.showSaveAs) SaveAsDialog(state)
    if (state.showOpen) OpenDialog(state)
    if (state.showWidgetPicker) WidgetPickerDialog(state)
    if (state.pendingWidgetBoxResize != null) WidgetBoxResizeDialog(state)
}

@Composable
private fun EditorHeader(state: EditorState, onBack: () -> Unit) {
    val page = state.doc.pages[state.pageIndex]
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.persistActive(); onBack() }) { Text("\u2190 Done") }
            Column(Modifier.weight(1f)) {
                Text(state.doc.profileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(stringResource(R.string.workspace_page, page.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { state.showWidgetStudio = true }) { Text("\u2318", style = MaterialTheme.typography.titleMedium) }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                Text(
                    stringResource(state.mode.shortRes), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(state: EditorState, onSocial: () -> Unit) {
    val page = state.doc.pages[state.pageIndex]
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.workspace_page, page.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSocial) { Text("◎ Social", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { state.showWidgetStudio = true }) {
                Text("⌘ ${stringResource(R.string.open_widget_studio)}", style = MaterialTheme.typography.labelMedium)
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                Text(
                    stringResource(state.mode.labelRes), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PageWorkspace(state:EditorState,modifier:Modifier=Modifier){
    val renderRevision=state.renderRevision
    val page=state.doc.pages[state.pageIndex]
    val cam=state.cameraTarget()
    var workspaceZoom by remember(state.pageIndex) { mutableFloatStateOf(1f) }
    val left by animateFloatAsState(cam.x,tween(220),label="cameraLeft")
    val top by animateFloatAsState(cam.y,tween(220),label="cameraTop")
    val width by animateFloatAsState(cam.w,tween(220),label="cameraWidth")
    val height by animateFloatAsState(cam.h,tween(220),label="cameraHeight")
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    BoxWithConstraints(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(state.pageIndex) {
                // Observe at the initial pass so a second finger can turn an in-progress touch
                // into a viewport zoom without replacing the existing one-finger element gestures.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoomChange = event.calculateZoom()
                            if (zoomChange.isFinite() && zoomChange > 0f && abs(zoomChange - 1f) > .0001f) {
                                workspaceZoom = (workspaceZoom * zoomChange).coerceIn(.45f, 4f)
                            }
                            event.changes.forEach { change -> if (change.pressed) change.consume() }
                        }
                    }
                }
            },
        contentAlignment=Alignment.Center
    ){
        Canvas(Modifier.matchParentSize()) {
            val step = 26.dp.toPx()
            val radius = 1.15.dp.toPx()
            var x = step / 2f
            while (x < size.width) {
                var y = step / 2f
                while (y < size.height) {
                    drawCircle(gridColor, radius, Offset(x, y))
                    y += step
                }
                x += step
            }
        }
        val usableW=maxWidth-28.dp
        val usableH=maxHeight-28.dp
        val widthByHeight=usableH*page.aspectRatio
        val pageW=minOf(usableW,widthByHeight)
        val pageH=pageW/page.aspectRatio
        Surface(
            modifier = Modifier
                .size(pageW,pageH)
                .graphicsLayer {
                    scaleX = workspaceZoom
                    scaleY = workspaceZoom
                },
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            shadowElevation = 10.dp
        ) {
            Box(Modifier.fillMaxSize().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(12.dp))){
                ProfileCanvas(state,renderRevision,NRect(left,top,width,height),Modifier.fillMaxSize())
                if(state.mode==EditMode.Foreground){
                    Surface(
                        color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),
                        shape=RoundedCornerShape(bottomEnd=12.dp),
                        shadowElevation = 2.dp,
                        modifier=Modifier.align(Alignment.TopStart)
                    ){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            TextButton(onClick=state::exitBox){Text("←")}
                            Text(
                                state.text(R.string.box_zoom_hint,state.findElement(state.focusedBoxId)?.let{state.displayName(it)}?:""),
                                style=MaterialTheme.typography.labelMedium,
                                modifier=Modifier.padding(end=10.dp)
                            )
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
            shape = RoundedCornerShape(999.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .clickable { workspaceZoom = 1f }
        ) {
            Text(
                stringResource(R.string.zoom_hint,(workspaceZoom * 100).roundToInt()),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProfileCanvas(state:EditorState,renderRevision:Int,camera:NRect,modifier:Modifier){
    val renderStrings=RenderStrings(
        mediaKinds=listOf(stringResource(R.string.media_image),stringResource(R.string.media_audio),stringResource(R.string.media_video)),
        mediaSuffix=stringResource(R.string.media_preview_suffix),
        widgetSuffix=stringResource(R.string.widget_preview_suffix),
        widgetPaused=stringResource(R.string.widget_paused_short)
    )
    BoxWithConstraints(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(state.pageIndex,state.mode,state.focusedBoxId,camera){
                    detectDragGestures(
                        onDragStart={p->
                            val nx=camera.x+(p.x/size.width)*camera.w
                            val ny=camera.y+(p.y/size.height)*camera.h
                            state.beginDrag(nx,ny)
                        },
                        onDragEnd=state::endDrag,
                        onDragCancel=state::endDrag
                    ){change,dragAmount->
                        change.consume()
                        state.dragBy((dragAmount.x/size.width)*camera.w,(dragAmount.y/size.height)*camera.h)
                    }
                }
                .pointerInput(state.pageIndex,state.mode,state.focusedBoxId,camera){
                    detectTapGestures(
                        onTap={p:Offset->state.tapAt(camera.x+(p.x/size.width)*camera.w,camera.y+(p.y/size.height)*camera.h)},
                        onDoubleTap={p:Offset->state.tapAt(camera.x+(p.x/size.width)*camera.w,camera.y+(p.y/size.height)*camera.h,true)},
                        onLongPress={p:Offset->state.longPressAt(camera.x+(p.x/size.width)*camera.w,camera.y+(p.y/size.height)*camera.h)}
                    )
                }
        ){
            @Suppress("UNUSED_VARIABLE")val frameRevision=renderRevision
            val root=state.doc.pages[state.pageIndex].root
            val rootRect=Rect(-camera.x/camera.w*size.width,-camera.y/camera.h*size.height,(1f-camera.x)/camera.w*size.width,(1f-camera.y)/camera.h*size.height)
            drawBackground(rootRect,root.background)
            val stamps=root.children.filter{it.type==ElementType.Stamp}.sortedBy{it.rect.zIndex}
            stamps.forEach{
                drawElement(
                    it,rootRect,
                    if(state.mode==EditMode.Background)state.selectedId else "",
                    strings=renderStrings,
                    inlineEditingId=state.inlineTextEditId,
                    widgetLookup=state::widgetProgramFor
                )
            }
            if(state.mode!=EditMode.Background){
                root.children.filter{it.type!=ElementType.Stamp}.sortedBy{it.rect.zIndex}.forEach{
                    drawElement(
                        it,rootRect,state.selectedId,
                        selectChildren=state.mode==EditMode.Foreground,
                        strings=renderStrings,
                        inlineEditingId=state.inlineTextEditId,
                        widgetLookup=state::widgetProgramFor
                    )
                }
            }
        }

        val editId = state.inlineTextEditId
        val editElement = state.findElement(editId)
        val global = state.globalRectFor(editId)
        if (editElement?.type == ElementType.Text && global != null && state.mode == EditMode.Foreground) {
            val xFrac = (global.x - camera.x) / camera.w
            val yFrac = (global.y - camera.y) / camera.h
            val wFrac = global.w / camera.w
            val hFrac = global.h / camera.h
            val focusRequester = remember(editId) { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(editId) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            BasicTextField(
                value = state.inlineTextDraft,
                onValueChange = state::updateInlineText,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(editElement.textArgb),
                    fontSize = editElement.fontSize.sp,
                    fontWeight = if(editElement.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if(editElement.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if(editElement.underline) TextDecoration.Underline else TextDecoration.None,
                    textAlign = when(editElement.textAlign){
                        TextAlignMode.Left -> TextAlign.Left
                        TextAlignMode.Center -> TextAlign.Center
                        TextAlignMode.Right -> TextAlign.Right
                    }
                ),
                modifier = Modifier
                    .offset(x = maxWidth * xFrac, y = maxHeight * yFrac)
                    .size(
                        width = (maxWidth * wFrac).coerceAtLeast(24.dp),
                        height = (maxHeight * hFrac).coerceAtLeast(24.dp)
                    )
                    .focusRequester(focusRequester)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    .padding(1.dp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

internal fun DrawScope.drawBackground(r:Rect,bg:BackgroundSpec){if(bg.kind==BackgroundKind.Solid||bg.stops.size<2){drawRect(Color(bg.solidArgb),r.topLeft,r.size);return};val sorted=bg.stops.sortedBy{it.position}.map{it.position to Color(it.argb)}.toTypedArray();val start=Offset(r.left+bg.startX*r.width,r.top+bg.startY*r.height);val end=Offset(r.left+bg.endX*r.width,r.top+bg.endY*r.height);val d=end-start;if(d.x*d.x+d.y*d.y<.0001f){drawRect(sorted.last().second,r.topLeft,r.size);return};drawRect(Brush.linearGradient(colorStops=sorted,start=start,end=end),r.topLeft,r.size)}
private fun childRect(parent:Rect,q:RectSpec)=Rect(parent.left+q.x*parent.width,parent.top+q.y*parent.height,parent.left+(q.x+q.width)*parent.width,parent.top+(q.y+q.height)*parent.height)
private fun DrawScope.drawBorderSpec(r:Rect,d:DecorationRef,thickness:Float){val n=if(d.kind==DecorationKind.DecorationPack)d.basedOnBuiltin.ifBlank{"Thin1"}else d.builtinName;if(n=="None"||n.isBlank())return;val stroke=max(1f,thickness*density);val color=if(n=="Thick1")Color(0xFF2D3340)else Color(0xFF636873);if(n=="Dotted1")drawRect(color,r.topLeft,r.size,style=Stroke(stroke,pathEffect=PathEffect.dashPathEffect(floatArrayOf(6f,6f))))else{drawRect(color,r.topLeft,r.size,style=Stroke(stroke));if(n=="Thick1")drawRect(color,Offset(r.left+4f,r.top+4f),Size(max(0f,r.width-8f),max(0f,r.height-8f)),style=Stroke(stroke))}}
private fun DrawScope.drawStamp(r:Rect,e:Element){val n=if(e.stampDecoration.kind==DecorationKind.DecorationPack)e.stampDecoration.basedOnBuiltin.ifBlank{"Star1"}else e.stampDecoration.builtinName;val alpha=e.opacity.coerceIn(0f,1f);rotate(e.rotationDegrees,pivot=r.center){when(n){"Dot1"->drawOval(Color(0xFF7866DC).copy(alpha=alpha),r.topLeft,r.size);"Moon1"->{val outer=Path().apply{addOval(r)};val cut=Path().apply{addOval(Rect(r.left+r.width*.34f,r.top-r.height*.08f,r.right+r.width*.10f,r.bottom-r.height*.08f))};drawPath(Path.combine(PathOperation.Difference,outer,cut),Color(0xFFF6D36B).copy(alpha=alpha))};else->{val p=Path();val cx=r.center.x;val cy=r.center.y;val ro=min(r.width,r.height)*.48f;val ri=ro*.43f;repeat(10){i->val a=-PI/2+i*PI/5;val rr=if(i%2==0)ro else ri;val x=cx+cos(a).toFloat()*rr;val y=cy+sin(a).toFloat()*rr;if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};p.close();drawPath(p,if(n=="Star2")Color(0xFF4EA8DE).copy(alpha=alpha)else Color(0xFF7866DC).copy(alpha=alpha))}}}}
private fun DrawScope.drawTextWrapped(text:String,r:Rect,e:Element,color:Int=e.textArgb,align:Layout.Alignment?=null){
    drawIntoCanvas { canvas ->
        val paint = TextPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = e.fontSize * density
            isUnderlineText = e.underline
            typeface = when {
                e.bold && e.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                e.bold -> Typeface.DEFAULT_BOLD
                e.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }
        val a = align ?: when(e.textAlign){
            TextAlignMode.Left -> Layout.Alignment.ALIGN_NORMAL
            TextAlignMode.Center -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.Right -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(text,0,text.length,paint,max(1,r.width.toInt()))
            .setAlignment(a)
            .setIncludePad(false)
            .build()
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.translate(r.left,r.top)
        layout.draw(canvas.nativeCanvas)
        canvas.nativeCanvas.restore()
    }
}

internal fun DrawScope.drawElement(
    e:Element,
    parent:Rect,
    selectedId:String,
    selectChildren:Boolean=true,
    strings:RenderStrings,
    inlineEditingId:String?=null,
    widgetLookup:(Element)->WidgetProgram? = { null },
    imageLookup:(Element)->ImageBitmap? = { null }
){
    if(!e.rect.visible)return
    val r=childRect(parent,e.rect)
    when(e.type){
        ElementType.Block->{
            drawBackground(r,e.background)
            drawBorderSpec(r,e.border,e.borderThickness)
            e.children.sortedBy{it.rect.zIndex}.forEach{drawElement(it,r,if(selectChildren)selectedId else "",selectChildren,strings,inlineEditingId,widgetLookup,imageLookup)}
        }
        ElementType.Stamp->drawStamp(r,e)
        ElementType.Text-> if(e.id != inlineEditingId) drawTextWrapped(e.text,r,e)
        ElementType.Link-> drawTextWrapped(
            e.label,r,e.copy(fontSize=16f,textAlign=TextAlignMode.Center),
            if(e.targetType==LinkTargetType.External)0xFFB42832.toInt()else 0xFF3747B4.toInt(),
            Layout.Alignment.ALIGN_CENTER
        )
        ElementType.Button->{
            drawBackground(r,e.background)
            drawBorderSpec(r,e.buttonDecoration,1f)
            drawTextWrapped(e.label,r,e,e.textArgb,when(e.textAlign){
                TextAlignMode.Left->Layout.Alignment.ALIGN_NORMAL
                TextAlignMode.Center->Layout.Alignment.ALIGN_CENTER
                TextAlignMode.Right->Layout.Alignment.ALIGN_OPPOSITE
            })
        }
        ElementType.Media->{
            val bitmap = if(e.mediaKind==MediaKind.Image) imageLookup(e) else null
            if(bitmap!=null){
                drawImage(
                    image=bitmap,
                    dstOffset=IntOffset(r.left.toInt(),r.top.toInt()),
                    dstSize=IntSize(r.width.toInt().coerceAtLeast(1),r.height.toInt().coerceAtLeast(1))
                )
                return
            }
            drawRect(Color(0xFFE5E7EB),r.topLeft,r.size)
            drawRect(Color(0xFF6B7280),r.topLeft,r.size,style=Stroke(1f))
            drawTextWrapped("${strings.mediaKinds[e.mediaKind.ordinal]}\n${e.mediaTitle}\n${e.intrinsicWidth} × ${e.intrinsicHeight}\n${strings.mediaSuffix}",r,e.copy(fontSize=13f,textAlign=TextAlignMode.Center),0xFF374151.toInt(),Layout.Alignment.ALIGN_CENTER)
        }
        ElementType.Widget->{
            val program=widgetLookup(e)
            if(program!=null) drawWidgetProgramStatic(program,r,strings.widgetPaused)
            else {
                drawRect(Color(0xFFEEF0F4),r.topLeft,r.size)
                drawRect(Color(0xFF6B7280),r.topLeft,r.size,style=Stroke(1f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(7f,6f))))
                drawTextWrapped("${e.widgetLabel}\n${strings.widgetSuffix}",r,e.copy(fontSize=13f,textAlign=TextAlignMode.Center),0xFF374151.toInt(),Layout.Alignment.ALIGN_CENTER)
            }
        }
    }
    if(e.id==selectedId)drawRect(Color(0xFFC34F5D),Offset(r.left-2f,r.top-2f),Size(r.width+4f,r.height+4f),style=Stroke(2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(7f,5f))))
}

@Composable
private fun ToolStrip(state:EditorState, modifier: Modifier = Modifier){
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 5.dp,
        modifier = modifier.height(92.dp)
    ) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal=10.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.modes_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            EditMode.entries.forEach { mode ->
                val active = mode == state.mode
                ToolbarTile(
                    glyph = when(mode) {
                        EditMode.Background -> "◩"
                        EditMode.Boxes -> ""
                        EditMode.Foreground -> "⌗"
                    },
                    label = stringResource(mode.shortRes),
                    active = active,
                    onClick = { state.changeEditMode(mode) },
                    description = stringResource(mode.labelRes),
                    drawCube = mode == EditMode.Boxes
                )
            }
            VerticalDivider(Modifier.height(54.dp).padding(horizontal=5.dp))
            Text(
                stringResource(R.string.add_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            state.allowedTools().forEach { tool ->
                ToolbarTile(
                    glyph = tool.symbol,
                    label = stringResource(tool.compactRes),
                    active = tool == state.tool,
                    onClick = { state.add(tool) },
                    description = state.text(tool.descriptionRes),
                    drawCube = tool == EditorTool.Block
                )
            }
        }
    }
}

@Composable
private fun ToolbarTile(
    glyph: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    description: String,
    drawCube: Boolean = false
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 68.dp).height(62.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if(active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (drawCube) IsometricCubeIcon(Modifier.size(22.dp), color = LocalContentColor.current)
            else Text(glyph, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun Section(
    title:String,
    expanded:Boolean,
    onToggle:()->Unit,
    helpText:String?=null,
    enabled:Boolean=true,
    content:@Composable ColumnScope.()->Unit
){
    val shape=RoundedCornerShape(14.dp)
    val effectiveExpanded=expanded&&enabled
    val headerColor=if(enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor=if(enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape=shape,
        color = if(enabled) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f),
        modifier=Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,shape)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(headerColor),
                verticalAlignment=Alignment.CenterVertically
            ){
                TextButton(
                    onClick=onToggle,
                    enabled=enabled,
                    modifier=Modifier.weight(1f),
                    contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)
                ) {
                    Text(if(effectiveExpanded) "⌄" else "›",modifier=Modifier.width(16.dp),style=MaterialTheme.typography.titleMedium,color=textColor)
                    Text(title,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold,color=textColor,maxLines=1)
                }
                if(helpText!=null&&enabled) HelpBubble(helpText,Modifier.padding(end=4.dp))
            }
            AnimatedVisibility(effectiveExpanded){
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp),
                    content=content
                )
            }
        }
    }
}

@Composable
private fun HelpBubble(helpText:String,modifier:Modifier=Modifier){
    var open by remember{mutableStateOf(false)}
    Box(modifier){
        Surface(
            color=Color(0xFF3478D4),
            contentColor=Color.White,
            shape=RoundedCornerShape(50),
            modifier=Modifier.size(18.dp).clickable{open=true}
        ){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("?",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall)}}
        DropdownMenu(expanded=open,onDismissRequest={open=false}){
            Text(helpText,modifier=Modifier.widthIn(min=180.dp,max=280.dp).padding(12.dp),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IsometricCubeIcon(modifier:Modifier=Modifier,color:Color=Color.Unspecified){
    val actualColor=if(color==Color.Unspecified)LocalContentColor.current else color
    Canvas(modifier){
        val w=size.width;val h=size.height
        val top=Offset(w*.50f,h*.08f);val left=Offset(w*.12f,h*.30f);val right=Offset(w*.88f,h*.30f)
        val mid=Offset(w*.50f,h*.52f);val bottom=Offset(w*.50f,h*.94f);val leftBottom=Offset(w*.12f,h*.68f);val rightBottom=Offset(w*.88f,h*.68f)
        val stroke=Stroke(width=max(1.5f,w*.075f),cap=StrokeCap.Round,join=StrokeJoin.Round)
        val lines=listOf(top to left,top to right,left to mid,right to mid,mid to bottom,left to leftBottom,leftBottom to bottom,right to rightBottom,rightBottom to bottom,mid to bottom)
        lines.forEach{(a,b)->drawLine(actualColor,a,b,strokeWidth=stroke.width,cap=StrokeCap.Round)}
    }
}

@Composable
private fun DottedDivider(){
    val dividerColor=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(1.dp)){
        drawLine(dividerColor,Offset.Zero,Offset(size.width,0f),strokeWidth=1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx())))
    }
}

@Composable
private fun LabeledSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float> = 0f..1f,onChange:(Float)->Unit,onFinished:()->Unit){var live by remember(value,range.start,range.endInclusive){mutableFloatStateOf(value.coerceIn(range.start,range.endInclusive))};var dialog by remember{mutableStateOf(false)};var typed by remember{mutableStateOf("")};Column(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f));Text(String.format(Locale.US,"%.2f",live),style=MaterialTheme.typography.labelMedium,modifier=Modifier.pointerInput(label,live){detectTapGestures(onDoubleTap={typed=String.format(Locale.US,"%.3f",live);dialog=true})})};Slider(value=live,onValueChange={live=it;onChange(it)},valueRange=range,onValueChangeFinished=onFinished)};if(dialog){AlertDialog(onDismissRequest={dialog=false},title={Text(stringResource(R.string.enter_number,label))},text={OutlinedTextField(value=typed,onValueChange={typed=it},label={Text(stringResource(R.string.number_value))},singleLine=true)},confirmButton={Button(onClick={typed.toFloatOrNull()?.coerceIn(range.start,range.endInclusive)?.let{live=it;onChange(it);onFinished()};dialog=false}){Text(stringResource(R.string.apply))}},dismissButton={TextButton(onClick={dialog=false}){Text(stringResource(R.string.cancel))}})}}

@Composable
private fun Choice(label:String,current:String,choices:List<String>,onPick:(Int)->Unit){var open by remember{mutableStateOf(false)};Column(Modifier.fillMaxWidth()){Text(label,style=MaterialTheme.typography.labelMedium);Box{OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=10.dp)){Text(current,maxLines=1)};DropdownMenu(expanded=open,onDismissRequest={open=false}){choices.forEachIndexed{i,s->DropdownMenuItem(text={Text(s)},onClick={open=false;onPick(i)})}}}}}

@Composable
private fun PropertiesPanel(state:EditorState,modifier:Modifier=Modifier){
    @Suppress("UNUSED_VARIABLE") val rev=state.uiRevision
    val e=state.selected()?:return
    val scroll=rememberScrollState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation=3.dp,
        shadowElevation = 4.dp,
        modifier=modifier
    ) {
        Column(Modifier.fillMaxSize()) {
            // Keep the inspector identity + collapse control pinned. Only the body below
            // scrolls, so the sidebar can always be closed immediately.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.edit_panel),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(
                        state.displayName(e),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick={ state.finishInlineTextEdit(); state.panelCollapsed=true },
                    modifier = Modifier.size(40.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("›", style = MaterialTheme.typography.titleLarge) }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                ModeStatus(state)
                QuickObjectActions(state)
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ) {
                HierarchyTree(state)
                val depthEnabled=state.depthTopFirst().isNotEmpty()
                val moveSizeEnabled=!(state.mode==EditMode.Foreground&&e.id==state.focusedBoxId)
                val appearanceEnabled=when{
                    state.mode==EditMode.Foreground&&e.id==state.focusedBoxId->false
                    state.isPageRoot(e)&&state.mode!=EditMode.Background->false
                    else->e.type in listOf(ElementType.Block,ElementType.Stamp,ElementType.Text,ElementType.Button)
                }
                val contentEnabled=e.type in listOf(ElementType.Text,ElementType.Link,ElementType.Button,ElementType.Media,ElementType.Widget)
                Section(stringResource(R.string.section_depth),state.sectionExpanded(PanelSection.Depth),{state.toggleSection(PanelSection.Depth)},stringResource(R.string.depth_help),depthEnabled){DepthPanel(state)}
                Section(stringResource(R.string.section_move_size),state.sectionExpanded(PanelSection.MoveSize),{state.toggleSection(PanelSection.MoveSize)},stringResource(R.string.move_size_help),moveSizeEnabled){MoveSizePanel(state,e)}
                Section(stringResource(R.string.section_appearance),state.sectionExpanded(PanelSection.Appearance),{state.toggleSection(PanelSection.Appearance)},stringResource(R.string.appearance_help),appearanceEnabled){AppearancePanel(state,e)}
                if(contentEnabled)
                    Section(stringResource(R.string.section_content),state.sectionExpanded(PanelSection.Content),{state.toggleSection(PanelSection.Content)},stringResource(R.string.content_help),true){ContentPanel(state,e)}
                Section(stringResource(R.string.section_file),state.sectionExpanded(PanelSection.FileActions),{state.toggleSection(PanelSection.FileActions)},stringResource(R.string.file_actions_help),true){FilePanel(state)}
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HierarchyTree(state: EditorState) {
    val expandedPages = remember { mutableStateMapOf<String, Boolean>() }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable { state.hierarchyCollapsed = !state.hierarchyCollapsed }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.hierarchyCollapsed) "›" else "⌄",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    stringResource(R.string.hierarchy),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    state.doc.pages.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(!state.hierarchyCollapsed) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                state.doc.pages.forEachIndexed { pi, page ->
            val isCurrentPage = pi == state.pageIndex
            val expanded = expandedPages[page.id] ?: isCurrentPage
            val entries = state.hierarchyForPage(pi)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if(isCurrentPage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { expandedPages[page.id] = !expanded },
                            modifier = Modifier.size(34.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(if(expanded) "⌄" else "›", style = MaterialTheme.typography.titleMedium) }
                        Row(
                            Modifier.weight(1f).clickable {
                                state.select(HierarchyEntry(page.name, pi, page.root.id))
                            }.padding(horizontal = 4.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("▤", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.page_prefix, page.name),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if(isCurrentPage) FontWeight.Bold else FontWeight.SemiBold
                            )
                            Text(
                                entries.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                    AnimatedVisibility(expanded) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if(entries.isEmpty()) {
                                Text(
                                    stringResource(R.string.page_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            entries.forEach { entry ->
                                HierarchyRow(state, entry)
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }
}

@Composable
private fun HierarchyRow(state: EditorState, entry: HierarchyEntry) {
    val selected = state.pageIndex == entry.pageIndex && state.selectedId == entry.elementId
    val glyph = when(entry.type) {
        ElementType.Block -> ""
        ElementType.Text -> "T"
        ElementType.Link -> "↗"
        ElementType.Button -> "▰"
        ElementType.Stamp -> "★"
        ElementType.Media -> "▧"
        ElementType.Widget -> "⌘"
        null -> "•"
    }
    Surface(
        color = if(selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.fillMaxWidth().clickable { state.select(entry) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = (8 + entry.depth * 16).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if(entry.type==ElementType.Block)
                Box(Modifier.width(25.dp),contentAlignment=Alignment.CenterStart){IsometricCubeIcon(Modifier.size(18.dp),if(selected)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)}
            else Text(glyph, modifier = Modifier.width(25.dp), color = if(selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                entry.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ModeStatus(state:EditorState){
    Surface(
        color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.74f),
        shape=RoundedCornerShape(11.dp),
        modifier=Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(11.dp))
    ){
        Row(Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
            if(state.mode==EditMode.Background){
                Text("◩",style=MaterialTheme.typography.titleLarge,modifier=Modifier.width(34.dp),textAlign=TextAlign.Center)
                Text(stringResource(R.string.mode_background),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            }else{
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(45.dp)){
                    IsometricCubeIcon(Modifier.size(24.dp),MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.this_box_top),style=MaterialTheme.typography.labelSmall,lineHeight=10.sp)
                    Text(stringResource(R.string.this_box_bottom),style=MaterialTheme.typography.labelSmall,lineHeight=10.sp)
                }
                Spacer(Modifier.weight(1f))
                if(state.mode==EditMode.Boxes&&state.findElement(state.focusedBoxId)!=null)
                    TextButton(onClick={state.enterBox(state.focusedBoxId!!)}){Text(stringResource(R.string.edit_contents),maxLines=1)}
                if(state.mode==EditMode.Foreground)
                    TextButton(onClick=state::exitBox){Text(stringResource(R.string.exit_box),maxLines=1)}
            }
        }
    }
}

@Composable
private fun QuickObjectActions(state:EditorState){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(
            onClick=state::deleteSelected,
            modifier=Modifier.weight(1f).height(42.dp).semantics{contentDescription=state.text(R.string.delete)},
            contentPadding=PaddingValues(0.dp)
        ){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("✕",style=MaterialTheme.typography.titleMedium)}}
        OutlinedButton(
            onClick=state::duplicateSelected,
            modifier=Modifier.weight(1f).height(42.dp).semantics{contentDescription=state.text(R.string.duplicate)},
            contentPadding=PaddingValues(0.dp)
        ){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("⧉",style=MaterialTheme.typography.titleMedium)}}
    }
}

@Composable
private fun DepthPanel(state:EditorState){
    @Suppress("UNUSED_VARIABLE") val depthRevision=state.depthRevision
    val items=state.depthTopFirst()
    if(items.isEmpty()){Text(stringResource(R.string.depth_empty),style=MaterialTheme.typography.bodySmall);return}
    val scroll=rememberScrollState();val scope=rememberCoroutineScope()
    Surface(
        color=Color.White,
        shape=RoundedCornerShape(10.dp),
        modifier=Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(10.dp))
    ){
        Column(Modifier.fillMaxWidth().heightIn(max=270.dp).verticalScroll(scroll)){
            items.forEachIndexed{index,item->
                val dragging=state.depthArmedId==item.id
                var accum by remember(item.id){mutableFloatStateOf(0f)}
                Surface(
                    color=if(dragging)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.75f)else Color.White,
                    shadowElevation=if(dragging)8.dp else 0.dp,
                    shape=RoundedCornerShape(if(dragging)9.dp else 0.dp),
                    modifier=Modifier.fillMaxWidth().pointerInput(item.id){
                        detectTapGestures(onTap={state.selectedId=item.id})
                    }.pointerInput(item.id){
                        detectDragGesturesAfterLongPress(
                            onDragStart={state.startDepthDrag(item.id)},
                            onDragEnd={accum=0f;state.finishDepthDrag()},
                            onDragCancel={accum=0f;state.finishDepthDrag()}
                        ){change,drag->
                            change.consume()
                            state.depthInvalidDrop=change.position.x< -24f||change.position.x>size.width+24f
                            if(!state.depthInvalidDrop){
                                accum+=drag.y
                                if(abs(accum)>30f){state.shiftDepth(item.id,if(accum<0)-1 else 1);accum=0f}
                                if(change.position.y< -6f)scope.launch{scroll.scrollBy(-28f)}
                                else if(change.position.y>size.height+6f)scope.launch{scroll.scrollBy(28f)}
                            }
                        }
                    }
                ){
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("⋮⋮",modifier=Modifier.padding(end=9.dp),color=MaterialTheme.colorScheme.outline)
                        Text(state.displayName(item),modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(dragging)Text(if(state.depthInvalidDrop)"⊘" else "↕",color=if(state.depthInvalidDrop)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)
                    }
                }
                if(index<items.lastIndex)DottedDivider()
            }
        }
    }
    if(state.depthInvalidDrop)Text(stringResource(R.string.invalid_drop),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun MoveSizePanel(state:EditorState,e:Element){
    if(state.mode==EditMode.Foreground&&e.id==state.focusedBoxId)return
    if(!state.isPageRoot(e)){
        LabeledSlider(stringResource(R.string.x_position),e.rect.x,0f..1f,{v->state.continuousEdit{it.rect.x=v.coerceAtMost(1f-it.rect.width)}},state::endContinuous)
        LabeledSlider(stringResource(R.string.y_position),e.rect.y,0f..1f,{v->state.continuousEdit{it.rect.y=v.coerceAtMost(1f-it.rect.height)}},state::endContinuous)
        LabeledSlider(stringResource(R.string.width),e.rect.width,.02f..1f,{v->state.continuousEdit{it.rect.width=v.coerceAtMost(1f-it.rect.x)}},state::endContinuous)
        LabeledSlider(stringResource(R.string.height),e.rect.height,.02f..1f,{v->state.continuousEdit{it.rect.height=v.coerceAtMost(1f-it.rect.y)}},state::endContinuous)
    }else LabeledSlider(stringResource(R.string.page_aspect),state.doc.pages[state.pageIndex].aspectRatio,.20f..1.20f,{v->state.continuousEditDoc{it.pages[state.pageIndex].aspectRatio=v}},state::endContinuous)
    if(e.type==ElementType.Stamp){
        LabeledSlider(stringResource(R.string.rotation),e.rotationDegrees,0f..360f,{v->state.continuousEdit{it.rotationDegrees=v}},state::endContinuous)
        LabeledSlider(stringResource(R.string.opacity),e.opacity,0f..1f,{v->state.continuousEdit{it.opacity=v}},state::endContinuous)
    }
}

@Composable
private fun AppearancePanel(state:EditorState,e:Element){
    if(state.mode==EditMode.Foreground&&e.id==state.focusedBoxId)return
    if(state.isPageRoot(e)&&state.mode!=EditMode.Background)return
    when(e.type){
        ElementType.Block -> BlockAppearance(state,e)
        ElementType.Stamp -> StampAppearance(state,e)
        ElementType.Text -> TextStyleControls(state,e)
        ElementType.Button -> ButtonAppearance(state,e)
        else -> Unit
    }
}

@Composable
private fun BackgroundControls(state:EditorState,e:Element,colourLabel:String){
    val allowTransparent=e.type==ElementType.Block&&!state.isPageRoot(e)
    fun modelModeIndex():Int {
        val alpha=(e.background.solidArgb ushr 24) and 0xFF
        return when{
            allowTransparent&&e.background.kind==BackgroundKind.Solid&&alpha==0->2
            e.background.kind==BackgroundKind.LinearGradient->1
            else->0
        }
    }

    // BackgroundSpec is deliberately a lightweight mutable wire-model object.  The
    // canvas is invalidated immediately when it changes, but Compose cannot observe
    // its individual fields directly.  Keep a tiny UI-side mode state so the selector
    // label and its mode-specific controls change in the same tap, rather than waiting
    // for some unrelated selection/recomposition event.
    var liveModeIndex by remember(e.id) { mutableIntStateOf(modelModeIndex()) }
    val uiRevision=state.uiRevision
    LaunchedEffect(e.id,uiRevision){ liveModeIndex=modelModeIndex() }

    BackgroundModeChoice(e.background,liveModeIndex,allowTransparent){idx->
        liveModeIndex=idx
        state.editSelected{item->
            when(idx){
                0->{
                    item.background.kind=BackgroundKind.Solid
                    if(((item.background.solidArgb ushr 24)and 0xFF)==0)item.background.solidArgb=0xFFF7EDEF.toInt()
                }
                1->{
                    item.background.kind=BackgroundKind.LinearGradient
                    if(item.background.stops.size<2)item.background.stops=mutableListOf(GradientStop(0f,0xFFFBE5E8.toInt()),GradientStop(1f,0xFFE9B8BF.toInt()))
                    if(item.background.stops.all{((it.argb ushr 24)and 0xFF)==0})item.background.stops=mutableListOf(GradientStop(0f,0xFFFBE5E8.toInt()),GradientStop(1f,0xFFE9B8BF.toInt()))
                }
                2->{item.background.kind=BackgroundKind.Solid;item.background.solidArgb=0x00000000}
            }
        }
    }
    if(liveModeIndex==0){
        ColorField(colourLabel,e.background.solidArgb){c->state.editSelected{it.background.solidArgb=(0xFF000000L or (c.toLong() and 0xFFFFFF)).toInt()}}
    }else if(liveModeIndex==1){
        Text(stringResource(R.string.fade_direction),style=MaterialTheme.typography.titleSmall)
        LabeledSlider(stringResource(R.string.start_x),e.background.startX,0f..1f,{v->state.continuousEdit{it.background.startX=v}},state::endContinuous)
        LabeledSlider(stringResource(R.string.start_y),e.background.startY,0f..1f,{v->state.continuousEdit{it.background.startY=v}},state::endContinuous)
        LabeledSlider(stringResource(R.string.end_x),e.background.endX,0f..1f,{v->state.continuousEdit{it.background.endX=v}},state::endContinuous)
        LabeledSlider(stringResource(R.string.end_y),e.background.endY,0f..1f,{v->state.continuousEdit{it.background.endY=v}},state::endContinuous)
        val stops=e.background.stops
        if(state.selectedGradientStop>=stops.size)state.selectedGradientStop=stops.lastIndex.coerceAtLeast(0)
        FadeColourList(state,e)
        if(stops.isNotEmpty()){
            val stop=stops[state.selectedGradientStop.coerceIn(stops.indices)]
            LabeledSlider(stringResource(R.string.colour_position),stop.position,0f..1f,{v->state.continuousEdit{stop.position=v;it.background.stops.sortBy{s->s.position}}},{state.selectedGradientStop=e.background.stops.indexOfFirst{it===stop}.coerceAtLeast(0);state.endContinuous()})
            ColorField(stringResource(R.string.colour_hex),stop.argb){c->state.editSelected{it.background.stops[state.selectedGradientStop].argb=c}}
        }
    }
}

@Composable
private fun FadeColourList(state:EditorState,e:Element){
    val stops=e.background.stops
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            // Intentionally two lines: the inspector is narrow and this keeps the
            // label readable instead of letting Compose squeeze it into vertical text.
            Text(
                stringResource(R.string.fade_colours),
                modifier=Modifier.weight(1f),
                fontWeight=FontWeight.SemiBold,
                maxLines=2
            )
            Column(
                verticalArrangement=Arrangement.spacedBy(3.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                OutlinedButton(
                    onClick={
                        if(stops.size<VspfLimits.MAX_GRADIENT_STOPS){
                            val insertAt=(state.selectedGradientStop+1).coerceIn(0,stops.size)
                            val before=stops.getOrNull((insertAt-1).coerceAtLeast(0))
                            val after=stops.getOrNull(insertAt)
                            val position=when{
                                before!=null&&after!=null->(before.position+after.position)/2f
                                before!=null->((before.position+1f)/2f).coerceAtMost(1f)
                                after!=null->(after.position/2f).coerceAtLeast(0f)
                                else->.5f
                            }
                            val color=before?.argb?:after?.argb?:0xFFFFFFFF.toInt()
                            state.editSelected{item->item.background.stops.add(insertAt,GradientStop(position,color))}
                            state.selectedGradientStop=insertAt
                        }
                    },
                    enabled=stops.size<VspfLimits.MAX_GRADIENT_STOPS,
                    contentPadding=PaddingValues(0.dp),
                    modifier=Modifier.size(width=40.dp,height=30.dp)
                ){Text("+",fontWeight=FontWeight.Bold)}
                OutlinedButton(
                    onClick={
                        if(stops.size>2){
                            val removeAt=state.selectedGradientStop.coerceIn(stops.indices)
                            state.editSelected{item->item.background.stops.removeAt(removeAt)}
                            state.selectedGradientStop=removeAt.coerceAtMost(stops.lastIndex)
                        }
                    },
                    enabled=stops.size>2,
                    contentPadding=PaddingValues(0.dp),
                    modifier=Modifier.size(width=40.dp,height=30.dp)
                ){Text("−",fontWeight=FontWeight.Bold)}
            }
        }
        Column(
            Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(10.dp)).padding(6.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)
        ){
            stops.forEachIndexed{index,stop->
                val selected=index==state.selectedGradientStop
                val shape=RoundedCornerShape(8.dp)
                val borderColor=if(selected)Color(0xFF3A2F31) else MaterialTheme.colorScheme.outlineVariant
                val borderWidth=if(selected)2.dp else 1.dp
                val textColor=colourLabelColor(stop.argb)
                Box(
                    Modifier.fillMaxWidth().height(42.dp)
                        .background(Color(stop.argb),shape)
                        .border(borderWidth,borderColor,shape)
                        .clickable{state.selectedGradientStop=index},
                    contentAlignment=Alignment.CenterStart
                ){
                    Text(
                        stringResource(R.string.colour_number,index+1),
                        modifier=Modifier.padding(horizontal=12.dp),
                        color=textColor,
                        fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun colourLabelColor(argb:Int):Color{
    val r=(argb shr 16) and 0xFF
    val g=(argb shr 8) and 0xFF
    val b=argb and 0xFF
    val luminance=(0.299*r+0.587*g+0.114*b)/255.0
    return if(luminance>0.60)Color(0xFF292326) else Color.White
}

@Composable
private fun BackgroundModeChoice(bg:BackgroundSpec,modeIndex:Int,allowTransparent:Boolean,onPick:(Int)->Unit){
    var open by remember{mutableStateOf(false)}
    val shape=RoundedCornerShape(11.dp)
    Box{
        Box(
            Modifier.fillMaxWidth().height(48.dp).border(1.dp,MaterialTheme.colorScheme.outline,shape).clickable{open=true},
            contentAlignment=Alignment.Center
        ){
            Canvas(Modifier.matchParentSize()){
                when(modeIndex){
                    0->drawRoundRect(Color(bg.solidArgb),cornerRadius=CornerRadius(11.dp.toPx(),11.dp.toPx()))
                    1->{
                        val sorted=bg.stops.sortedBy{it.position}.map{it.position to Color(it.argb)}.toTypedArray()
                        val brush=if(sorted.size>=2)Brush.linearGradient(colorStops=sorted,start=Offset(bg.startX*size.width,bg.startY*size.height),end=Offset(bg.endX*size.width,bg.endY*size.height))else SolidColor(Color(0xFFFBE5E8))
                        drawRoundRect(brush,cornerRadius=CornerRadius(11.dp.toPx(),11.dp.toPx()))
                    }
                    else->{
                        val cell=10.dp.toPx();var y=0f;var row=0
                        while(y<size.height){var x=0f;var col=0;while(x<size.width){drawRect(if((row+col)%2==0)Color(0xFFF5F5F5)else Color(0xFFDCDCDC),Offset(x,y),Size(cell,cell));x+=cell;col++};y+=cell;row++}
                    }
                }
            }
            Surface(color=Color.White.copy(alpha=.86f),shape=RoundedCornerShape(8.dp)){
                Text(when(modeIndex){0->stringResource(R.string.solid);1->stringResource(R.string.fade);else->stringResource(R.string.transparent)},modifier=Modifier.padding(horizontal=14.dp,vertical=6.dp),fontWeight=FontWeight.SemiBold,color=Color(0xFF5C3037))
            }
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}){
            DropdownMenuItem(text={Text(stringResource(R.string.solid))},onClick={open=false;onPick(0)})
            DropdownMenuItem(text={Text(stringResource(R.string.fade))},onClick={open=false;onPick(1)})
            if(allowTransparent)DropdownMenuItem(text={Text(stringResource(R.string.transparent))},onClick={open=false;onPick(2)})
        }
    }
}

@Composable
private fun DecorationBorderChoice(state:EditorState,e:Element,button:Boolean=false){
    val borderIds=listOf("None","Thin1","Thick1","Dotted1")
    val labels=listOf(
        stringResource(R.string.builtin_none),
        stringResource(R.string.builtin_thin1),
        stringResource(R.string.builtin_thick1),
        stringResource(R.string.builtin_dotted1),
        stringResource(R.string.custom_fallback_thin)
    )
    val ref=if(button)e.buttonDecoration else e.border
    val currentIndex=if(ref.kind==DecorationKind.DecorationPack)4 else borderIds.indexOf(ref.builtinName.ifBlank{"None"}).coerceAtLeast(0)
    Choice(stringResource(R.string.border),labels[currentIndex],labels){idx->
        state.editSelected{x->
            val next=if(idx==4)DecorationRef(DecorationKind.DecorationPack,"","VLD0:future-decoration-pack",1,"future-hash","Thin1")
            else DecorationRef(builtinName=borderIds[idx],basedOnBuiltin=borderIds[idx])
            if(button)x.buttonDecoration=next else x.border=next
        }
    }
}

@Composable
private fun BlockAppearance(state:EditorState,e:Element){
    BackgroundControls(state,e,stringResource(R.string.background_colour))
    DecorationBorderChoice(state,e,false)
}

@Composable
private fun StampAppearance(state:EditorState,e:Element){
    val stampIds=listOf("Star1","Star2","Moon1","Dot1")
    val labels=listOf(
        stringResource(R.string.builtin_star1),stringResource(R.string.builtin_star2),
        stringResource(R.string.builtin_moon1),stringResource(R.string.builtin_dot1),
        stringResource(R.string.custom_fallback_star)
    )
    val currentIndex=if(e.stampDecoration.kind==DecorationKind.DecorationPack)4 else stampIds.indexOf(e.stampDecoration.builtinName).coerceAtLeast(0)
    Choice(stringResource(R.string.stamp),labels[currentIndex],labels){idx->
        state.editSelected{x->
            x.stampDecoration=if(idx==4)DecorationRef(DecorationKind.DecorationPack,"","VLD0:future-decoration-pack",2,"future-hash","Star1")
            else DecorationRef(builtinName=stampIds[idx],basedOnBuiltin=stampIds[idx])
            state.refreshAutoName(x)
        }
    }
}

@Composable
private fun TextStyleControls(state:EditorState,e:Element){
    val fontIds=listOf("Default1")
    val fontLabels=listOf(stringResource(R.string.font_default))
    Choice(stringResource(R.string.font),fontLabels[fontIds.indexOf(e.fontId).coerceAtLeast(0)],fontLabels){idx->state.editSelected{it.fontId=fontIds[idx]}}
    LabeledSlider(stringResource(R.string.font_size),e.fontSize,8f..96f,{v->state.continuousEdit{it.fontSize=v}},state::endContinuous)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        FilterChip(selected=e.bold,onClick={state.editSelected{it.bold=!it.bold}},label={Text("B",fontWeight=FontWeight.Bold)},modifier=Modifier.weight(1f))
        FilterChip(selected=e.italic,onClick={state.editSelected{it.italic=!it.italic}},label={Text("I",fontStyle=FontStyle.Italic)},modifier=Modifier.weight(1f))
        FilterChip(selected=e.underline,onClick={state.editSelected{it.underline=!it.underline}},label={Text("U",textDecoration=TextDecoration.Underline)},modifier=Modifier.weight(1f))
    }
    Text(
        listOf(
            if(e.bold)stringResource(R.string.bold)else "",
            if(e.italic)stringResource(R.string.italic)else "",
            if(e.underline)stringResource(R.string.underline)else ""
        ).filter{it.isNotBlank()}.joinToString(" · ").ifBlank{" "},
        style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant
    )
    ColorField(stringResource(R.string.text_colour),e.textArgb){c->state.editSelected{it.textArgb=c}}
    val alignLabels=listOf(stringResource(R.string.align_left),stringResource(R.string.align_center),stringResource(R.string.align_right))
    Choice(stringResource(R.string.text_alignment),alignLabels[e.textAlign.ordinal],alignLabels){idx->state.editSelected{it.textAlign=TextAlignMode.entries[idx]}}
}

@Composable
private fun ButtonAppearance(state:EditorState,e:Element){
    BackgroundControls(state,e,stringResource(R.string.button_colour))
    DecorationBorderChoice(state,e,true)
    HorizontalDivider()
    TextStyleControls(state,e)
    OutlinedButton(onClick={state.toast(R.string.button_image_not_implemented)},modifier=Modifier.fillMaxWidth()){
        Text("▧ ${stringResource(R.string.button_image)}")
    }
}

@Composable
private fun BufferedEditorTextField(
    key:String,
    value:String,
    label:String,
    modifier:Modifier=Modifier,
    singleLine:Boolean=false,
    onLive:(String)->Unit,
    onFinished:()->Unit
){
    var draft by remember(key){mutableStateOf(value)}
    var focused by remember(key){mutableStateOf(false)}
    LaunchedEffect(value){if(!focused&&draft!=value)draft=value}
    OutlinedTextField(
        value=draft,
        onValueChange={draft=it;onLive(it)},
        label={Text(label)},
        modifier=modifier.onFocusChanged{focus->
            val was=focused
            focused=focus.isFocused
            if(was&&!focused)onFinished()
        },
        singleLine=singleLine
    )
}

@Composable
private fun ContentPanel(state:EditorState,e:Element){
    when(e.type){
        ElementType.Text->{
            Text(stringResource(R.string.direct_text_edit_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            BufferedEditorTextField(
                key="${e.id}:text",value=e.text,label=stringResource(R.string.text_content),
                modifier=Modifier.fillMaxWidth().heightIn(min=100.dp),
                onLive={v->state.continuousEdit{it.text=v;state.refreshAutoName(it)}},
                onFinished=state::endContinuous
            )
        }
        ElementType.Link,ElementType.Button->TargetProperties(state,e)
        ElementType.Media->MediaProperties(state,e)
        ElementType.Widget->WidgetProperties(state,e)
        else->Unit
    }
}

@Composable
private fun TargetProperties(state:EditorState,e:Element){
    BufferedEditorTextField(
        key="${e.id}:label",value=e.label,
        label=stringResource(if(e.type==ElementType.Button)R.string.button_label else R.string.link_label),
        modifier=Modifier.fillMaxWidth(),
        onLive={v->state.continuousEdit{it.label=v;state.refreshAutoName(it)}},
        onFinished=state::endContinuous
    )
    val targetLabels=listOf(
        stringResource(R.string.target_page),stringResource(R.string.target_profile),stringResource(R.string.target_post),
        stringResource(R.string.target_community),stringResource(R.string.target_dht),stringResource(R.string.target_external)
    )
    Choice(stringResource(R.string.link_type),targetLabels[e.targetType.ordinal],targetLabels){idx->state.editSelected{it.targetType=LinkTargetType.entries[idx]}}
    BufferedEditorTextField(
        key="${e.id}:target",value=e.target,label=stringResource(R.string.target),modifier=Modifier.fillMaxWidth(),
        onLive={v->state.continuousEdit{it.target=v}},onFinished=state::endContinuous
    )
}

@Composable
private fun MediaProperties(state:EditorState,e:Element){
    val kindLabels=listOf(stringResource(R.string.media_image),stringResource(R.string.media_audio),stringResource(R.string.media_video))
    Choice(stringResource(R.string.media_kind),kindLabels[e.mediaKind.ordinal],kindLabels){idx->state.editSelected{it.mediaKind=MediaKind.entries[idx]}}
    BufferedEditorTextField(
        key="${e.id}:mediaTitle",value=e.mediaTitle,label=stringResource(R.string.media_title),modifier=Modifier.fillMaxWidth(),
        onLive={v->state.continuousEdit{it.mediaTitle=v;state.refreshAutoName(it)}},onFinished=state::endContinuous
    )
    BufferedEditorTextField(
        key="${e.id}:mediaDescription",value=e.mediaDescription,label=stringResource(R.string.media_description),modifier=Modifier.fillMaxWidth().heightIn(min=90.dp),
        onLive={v->state.continuousEdit{it.mediaDescription=v}},onFinished=state::endContinuous
    )
    BufferedEditorTextField(
        key="${e.id}:mediaRecord",value=e.mediaRecordKey,label=stringResource(R.string.media_record_key),modifier=Modifier.fillMaxWidth(),
        onLive={v->state.continuousEdit{it.mediaRecordKey=v}},onFinished=state::endContinuous
    )
}

@Composable
private fun FilePanel(state:EditorState){
    BufferedEditorTextField(
        key="profile:name",value=state.doc.profileName,label=stringResource(R.string.profile_name),modifier=Modifier.fillMaxWidth(),singleLine=true,
        onLive={v->state.continuousEditDoc{it.profileName=v}},onFinished=state::endContinuous
    )
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(onClick=state::undo,modifier=Modifier.weight(1f)){Text("↶ ${stringResource(R.string.undo)}",maxLines=1)}
        OutlinedButton(onClick=state::redo,modifier=Modifier.weight(1f)){Text("↷ ${stringResource(R.string.redo)}",maxLines=1)}
    }
    OutlinedButton(onClick={state.showOpen=true},modifier=Modifier.fillMaxWidth()){
        Text("▣ ${stringResource(R.string.open)}")
    }
    OutlinedButton(onClick=state::revert,modifier=Modifier.fillMaxWidth()){
        Text("↺ ${stringResource(R.string.revert)}")
    }
    Button(onClick=state::save,modifier=Modifier.fillMaxWidth()){
        Text("✓ ${stringResource(R.string.save)}")
    }
    OutlinedButton(
        onClick={state.saveAsText=state.currentFileName?.removeSuffix(".txt")?:state.doc.profileName;state.showSaveAs=true},
        modifier=Modifier.fillMaxWidth()
    ){
        Text("＋ ${stringResource(R.string.save_as)}")
    }
    Button(onClick=state::publishLocal,modifier=Modifier.fillMaxWidth()){
        Text("↑ ${stringResource(R.string.publish_live)}",maxLines=2)
    }
}

@Composable
internal fun ColorField(label:String,color:Int,onColor:(Int)->Unit){
    var text by remember(color){mutableStateOf(colorHex(color))}
    var pickerOpen by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
        ColorWheelButton(color=color,onClick={pickerOpen=true})
        OutlinedTextField(
            value=text,
            onValueChange={v->text=v;parseColor(v)?.let(onColor)},
            label={Text(label)},
            modifier=Modifier.fillMaxWidth(),
            singleLine=true
        )
    }
    if(pickerOpen){
        ColorWheelDialog(
            initialColor=color,
            onDismiss={pickerOpen=false},
            onApply={picked->text=colorHex(picked);onColor(picked);pickerOpen=false}
        )
    }
}

@Composable
private fun ColorWheelButton(color:Int,onClick:()->Unit){
    val wheelDescription=stringResource(R.string.colour_wheel)
    Surface(
        onClick=onClick,
        shape=RoundedCornerShape(14.dp),
        color=MaterialTheme.colorScheme.surfaceVariant,
        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        modifier=Modifier.size(52.dp).semantics{contentDescription=wheelDescription}
    ){
        Canvas(Modifier.fillMaxSize().padding(7.dp)){
            val ring=max(2f,3.dp.toPx())
            val r=min(size.width,size.height)/2f-ring
            val c=center
            drawCircle(
                Brush.sweepGradient(listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red),c),
                radius=r,center=c,style=Stroke(ring)
            )
            drawCircle(Color(color),radius=max(2f,r-ring*1.8f),center=c)
        }
    }
}

@Composable
private fun ColorWheelDialog(initialColor:Int,onDismiss:()->Unit,onApply:(Int)->Unit){
    val initialHsv=remember(initialColor){FloatArray(3).also{AndroidColor.colorToHSV(initialColor,it)}}
    var hue by remember(initialColor){mutableFloatStateOf(initialHsv[0])}
    var saturation by remember(initialColor){mutableFloatStateOf(initialHsv[1])}
    var value by remember(initialColor){mutableFloatStateOf(initialHsv[2])}
    var hex by remember(initialColor){mutableStateOf(colorHex(initialColor))}
    fun packed():Int=AndroidColor.HSVToColor(floatArrayOf(hue,saturation,value))
    fun updateHex(){hex=colorHex(packed())}
    fun applyPoint(p:Offset,width:Float,height:Float){
        val cx=width/2f;val cy=height/2f;val dx=p.x-cx;val dy=p.y-cy
        val radius=min(width,height)/2f
        saturation=(sqrt(dx*dx+dy*dy)/radius).coerceIn(0f,1f)
        // Keep pointer hue in the same coordinate system as the sweep-gradient:
        // 0° is at 3 o'clock (red), 120° is green and 240° is blue.
        var deg=Math.toDegrees(atan2(dy.toDouble(),dx.toDouble())).toFloat()
        while(deg<0f)deg+=360f
        while(deg>=360f)deg-=360f
        hue=deg
        updateHex()
    }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(stringResource(R.string.choose_colour))},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Canvas(
                    Modifier.size(260.dp)
                        .pointerInput(Unit){
                            detectTapGestures(onTap={p->applyPoint(p,size.width.toFloat(),size.height.toFloat())})
                        }
                        .pointerInput(Unit){
                            detectDragGestures(
                                onDragStart={p->applyPoint(p,size.width.toFloat(),size.height.toFloat())}
                            ){change,_->applyPoint(change.position,size.width.toFloat(),size.height.toFloat());change.consume()}
                        }
                ){
                    val r=min(size.width,size.height)/2f-2.dp.toPx()
                    drawCircle(
                        Brush.sweepGradient(listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red),center),
                        radius=r
                    )
                    drawCircle(Brush.radialGradient(listOf(Color.White,Color.Transparent),center,r),radius=r)
                    val a=Math.toRadians(hue.toDouble())
                    val mr=r*saturation
                    val marker=Offset(center.x+cos(a).toFloat()*mr,center.y+sin(a).toFloat()*mr)
                    drawCircle(Color.Black.copy(alpha=.65f),radius=7.dp.toPx(),center=marker,style=Stroke(2.dp.toPx()))
                    drawCircle(Color.White,radius=5.dp.toPx(),center=marker,style=Stroke(2.dp.toPx()))
                }
                Text(stringResource(R.string.brightness),style=MaterialTheme.typography.labelMedium,modifier=Modifier.fillMaxWidth())
                Slider(value=value,onValueChange={value=it;updateHex()},valueRange=0f..1f)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Surface(color=Color(packed()),shape=RoundedCornerShape(10.dp),modifier=Modifier.size(46.dp)){}
                    OutlinedTextField(
                        value=hex,
                        onValueChange={v->
                            hex=v
                            parseColor(v)?.let{c->
                                val hsv=FloatArray(3);AndroidColor.colorToHSV(c,hsv)
                                hue=hsv[0];saturation=hsv[1];value=hsv[2]
                            }
                        },
                        label={Text(stringResource(R.string.hex_colour))},
                        modifier=Modifier.weight(1f),
                        singleLine=true
                    )
                }
            }
        },
        confirmButton={Button(onClick={onApply(packed())}){Text(stringResource(R.string.apply))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}}
    )
}

private fun colorHex(c:Int)=String.format(Locale.US,"#%06X",c and 0xFFFFFF)
private fun parseColor(s:String):Int?=try{val t=s.trim().removePrefix("#");if(t.length!=6)null else(0xFF000000L or t.toLong(16)).toInt()}catch(_:Exception){null}

@Composable
private fun SaveAsDialog(state:EditorState){AlertDialog(onDismissRequest={state.showSaveAs=false},title={Text(stringResource(R.string.save_local_profile_as))},text={OutlinedTextField(value=state.saveAsText,onValueChange={state.saveAsText=it},label={Text(stringResource(R.string.draft_name))},singleLine=true)},confirmButton={Button(onClick={state.saveAs(state.saveAsText)}){Text(stringResource(R.string.save))}},dismissButton={TextButton(onClick={state.showSaveAs=false}){Text(stringResource(R.string.cancel))}})}
@Composable
private fun OpenDialog(state:EditorState){val files=state.localProfiles();AlertDialog(onDismissRequest={state.showOpen=false},title={Text(stringResource(R.string.open_local_profile))},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){if(files.isEmpty())Text(stringResource(R.string.no_saved_profiles));files.forEach{f->TextButton(onClick={state.open(f)},modifier=Modifier.fillMaxWidth()){Text(f.nameWithoutExtension,modifier=Modifier.fillMaxWidth())}}}},confirmButton={},dismissButton={TextButton(onClick={state.showOpen=false}){Text(stringResource(R.string.close))}})}

private fun DrawScope.drawWidgetProgramStatic(program: WidgetProgram, r: Rect, pausedText: String) {
    drawRect(Color(program.backgroundArgb), r.topLeft, r.size)
    program.nodes.filter { it.visible }.forEach { n ->
        val nr = Rect(
            r.left + n.x * r.width,
            r.top + n.y * r.height,
            r.left + (n.x + n.width) * r.width,
            r.top + (n.y + n.height) * r.height
        )
        when (n.type) {
            WidgetNodeType.Box -> drawRect(Color(n.backgroundArgb), nr.topLeft, nr.size)
            WidgetNodeType.Text, WidgetNodeType.Button -> {
                if (n.type == WidgetNodeType.Button) {
                    drawRoundRect(Color(n.backgroundArgb), nr.topLeft, nr.size, CornerRadius(6f, 6f))
                    drawRoundRect(Color(0x557D3440), nr.topLeft, nr.size, CornerRadius(6f,6f), style = Stroke(1f))
                }
                val fake = Element(
                    type = ElementType.Text,
                    text = n.text,
                    fontSize = n.fontSize,
                    textArgb = n.textArgb,
                    bold = n.bold,
                    italic = n.italic,
                    underline = n.underline,
                    textAlign = when(n.align){WidgetTextAlign.Left->TextAlignMode.Left;WidgetTextAlign.Center->TextAlignMode.Center;WidgetTextAlign.Right->TextAlignMode.Right}
                )
                drawTextWrapped(n.text, nr, fake, n.textArgb, when(n.align){WidgetTextAlign.Left->Layout.Alignment.ALIGN_NORMAL;WidgetTextAlign.Center->Layout.Alignment.ALIGN_CENTER;WidgetTextAlign.Right->Layout.Alignment.ALIGN_OPPOSITE})
            }
            WidgetNodeType.Stream -> {
                drawRoundRect(Color(0xFF242A31), nr.topLeft, nr.size, CornerRadius(6f,6f))
                val fake = Element(type=ElementType.Text,fontSize=12f,textAlign=TextAlignMode.Center,textArgb=0xFFFFFFFF.toInt())
                drawTextWrapped("▶\n${n.streamLabel}\n$pausedText", nr, fake, 0xFFFFFFFF.toInt(), Layout.Alignment.ALIGN_CENTER)
            }
        }
    }
}

@Composable
private fun WidgetBoxResizeDialog(state: EditorState) {
    val pending = state.pendingWidgetBoxResize ?: return
    AlertDialog(
        onDismissRequest = state::cancelWidgetBoxResize,
        title = { Text(stringResource(R.string.widget_resize_box_title)) },
        text = {
            Text(
                stringResource(
                    R.string.widget_resize_box_message,
                    pending.requestedWidth, pending.requestedHeight,
                    pending.currentWidth, pending.currentHeight
                )
            )
        },
        confirmButton = {
            Button(onClick = state::confirmWidgetBoxResize) { Text(stringResource(R.string.widget_resize_box_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = state::keepWidgetBoxSize) { Text(stringResource(R.string.widget_keep_box_size)) }
        }
    )
}

@Composable
private fun WidgetPickerDialog(state: EditorState) {
    val packages = remember(state.showWidgetPicker, state.uiRevision) { state.widgetRepo.all() }
    AlertDialog(
        onDismissRequest = { state.showWidgetPicker = false },
        title = { Text(stringResource(R.string.choose_widget)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (packages.isEmpty()) Text(stringResource(R.string.no_widgets), style = MaterialTheme.typography.bodySmall)
                packages.forEach { pkg ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.fillMaxWidth().clickable { state.placeWidget(pkg) }
                    ) {
                        Column(Modifier.padding(9.dp)) {
                            Text(pkg.manifest.name, fontWeight = FontWeight.SemiBold)
                            Text("${pkg.manifest.defaultWidth} × ${pkg.manifest.defaultHeight}", style = MaterialTheme.typography.labelSmall)
                            Text(pkg.manifest.sourceHash.take(18) + "…", style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
                OutlinedButton(onClick = state::placeBlankWidget, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.widget_blank_placeholder)) }
                Button(onClick = { state.showWidgetPicker = false; state.showWidgetStudio = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.widget_open_studio)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { state.showWidgetPicker = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun WidgetProperties(state: EditorState, e: Element) {
    val pkg = state.widgetPackageFor(e)
    val size = state.widgetLogicalSize(e)
    if (pkg == null && e.widgetRecordKey.isNotBlank()) {
        Text(stringResource(R.string.widget_missing_local), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (pkg != null && e.widgetSourceHash.isNotBlank() && pkg.manifest.sourceHash != e.widgetSourceHash) {
        Text(stringResource(R.string.widget_source_mismatch), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={state.acceptCurrentWidgetVersion(e)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.widget_accept_local_version))}
    }
    Text(stringResource(R.string.widget_current_package), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Text(pkg?.manifest?.name ?: e.widgetLabel, style = MaterialTheme.typography.bodyMedium)
    if (e.widgetSourceHash.isNotBlank()) {
        Text(stringResource(R.string.widget_expected_hash), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(e.widgetSourceHash.take(24) + "…", style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
    Text(stringResource(R.string.widget_default_size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("${e.widgetDefaultWidth} × ${e.widgetDefaultHeight}")
    if (e.widgetWarnOnResize && size != null) {
        val dw=e.widgetDefaultWidth.toInt(); val dh=e.widgetDefaultHeight.toInt()
        val changed = abs(size.first-dw) > max(4, (dw*.05f).roundToInt()) || abs(size.second-dh) > max(4,(dh*.05f).roundToInt())
        if (changed) Text(stringResource(R.string.widget_resize_warning,dw,dh), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { state.chooseReplacementFor(e) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_select_local)) }
        OutlinedButton(onClick = { state.showWidgetStudio = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_open_studio)) }
    }
}
