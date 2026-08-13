package com.veilysocial.profiledesigner

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class WidgetStudioTab { Visual, Code, Preview }

private class WidgetStudioState(private val context: Context, val repo: WidgetRepository) {
    var pkg by mutableStateOf<WidgetPackage?>(null)
    var program by mutableStateOf(makeStarterWidgetProgram())
    var compiledProgram by mutableStateOf(program.deepCopy())
    var source by mutableStateOf(VeilWidgetLanguage.sourceFor(program))
    var tab by mutableStateOf(WidgetStudioTab.Visual)
    var selectedNodeId by mutableStateOf(program.nodes.firstOrNull()?.id)
    var compileIssues by mutableStateOf<List<WidgetCompileIssue>>(emptyList())
    var status by mutableStateOf("")
    var showLibrary by mutableStateOf(false)
    var pendingCopyRelation by mutableStateOf<WidgetRelation?>(null)
    var revision by mutableIntStateOf(0)

    init {
        val existing = repo.all().firstOrNull()
        if (existing != null) open(existing) else {
            val starter = repo.createStarter()
            repo.createStreamSample()
            open(starter)
        }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun selectedNode(): WidgetNode? = program.nodes.firstOrNull { it.id == selectedNodeId }

    fun open(next: WidgetPackage) {
        pkg = next
        source = next.source
        val result = VeilWidgetLanguage.compile(source)
        compileIssues = result.issues
        program = result.program ?: runCatching { VeilWidgetBytecode.decode(next.bytecode) }.getOrElse { makeStarterWidgetProgram() }
        compiledProgram = runCatching { VeilWidgetBytecode.decode(next.bytecode) }.getOrElse { program.deepCopy() }
        selectedNodeId = program.nodes.firstOrNull()?.id
        status = if (repo.upstreamChanged(next)) context.getString(R.string.widget_upstream_changed) else context.getString(R.string.widget_ready)
        revision++
    }

    fun newWidget() {
        val p = makeStarterWidgetProgram().copy(name = context.getString(R.string.widget_new_name))
        program = p
        source = VeilWidgetLanguage.sourceFor(p)
        compiledProgram = p.deepCopy()
        pkg = null
        selectedNodeId = p.nodes.firstOrNull()?.id
        compileIssues = emptyList()
        status = context.getString(R.string.widget_unsaved)
        tab = WidgetStudioTab.Visual
        revision++
    }

    fun compileAndSave() {
        val (result, saved) = repo.compileAndSave(source, pkg)
        compileIssues = result.issues
        if (saved != null && result.program != null) {
            pkg = saved
            program = result.program
            compiledProgram = VeilWidgetBytecode.decode(result.bytecode)
            selectedNodeId = selectedNodeId?.takeIf { id -> program.nodes.any { it.id == id } } ?: program.nodes.firstOrNull()?.id
            status = context.getString(R.string.widget_compiled_saved)
            toast(status)
            revision++
        } else {
            status = context.getString(R.string.widget_compile_failed)
            tab = WidgetStudioTab.Code
        }
    }

    fun applyVisualChange(block: (WidgetProgram) -> Unit) {
        block(program)
        source = VeilWidgetLanguage.sourceFor(program)
        val compiled = VeilWidgetLanguage.compile(source)
        if (compiled.ok) compiledProgram = VeilWidgetBytecode.decode(compiled.bytecode)
        compileIssues = compiled.issues
        status = context.getString(R.string.widget_visual_changed)
        revision++
    }

    fun compileCodeOnly() {
        val result = VeilWidgetLanguage.compile(source)
        compileIssues = result.issues
        if (result.ok && result.program != null) {
            program = result.program
            compiledProgram = VeilWidgetBytecode.decode(result.bytecode)
            selectedNodeId = program.nodes.firstOrNull()?.id
            status = context.getString(R.string.widget_compile_ok)
            tab = WidgetStudioTab.Preview
            revision++
        } else status = context.getString(R.string.widget_compile_failed)
    }

    fun addNode(type: WidgetNodeType) {
        var n = 1
        val base = type.name.lowercase()
        while (program.nodes.any { it.id == "$base$n" }) n++
        val node = WidgetNode(type = type, id = "$base$n")
        when (type) {
            WidgetNodeType.Box -> { node.text = ""; node.backgroundArgb = 0xFFF7F3F4.toInt(); node.width = .7f; node.height = .45f }
            WidgetNodeType.Text -> { node.text = context.getString(R.string.widget_new_text); node.backgroundArgb = 0x00FFFFFF }
            WidgetNodeType.Button -> { node.text = context.getString(R.string.widget_new_button); node.backgroundArgb = 0xFFF4D8DC.toInt(); node.width = .45f }
            WidgetNodeType.Stream -> { node.text = ""; node.streamLabel = context.getString(R.string.widget_live_stream); node.width = .8f; node.height = .55f }
        }
        program.nodes += node
        selectedNodeId = node.id
        applyVisualChange { }
    }

    fun deleteSelected() {
        val id = selectedNodeId ?: return
        program.nodes.removeAll { it.id == id }
        program.events.removeAll { it.targetId == id }
        program.events.forEach { it.actions.removeAll { a -> a.targetId == id } }
        selectedNodeId = program.nodes.firstOrNull()?.id
        applyVisualChange { }
    }

    fun requestLink() { if(pkg!=null) pendingCopyRelation=WidgetRelation.Link }
    fun requestFork() { if(pkg!=null) pendingCopyRelation=WidgetRelation.Fork }
    fun confirmCopyWithBackup() {
        val origin=pkg?:return
        when(pendingCopyRelation){
            WidgetRelation.Link->{open(repo.linkFrom(origin));status=context.getString(R.string.widget_link_created)}
            WidgetRelation.Fork->{open(repo.forkFrom(origin));status=context.getString(R.string.widget_fork_created)}
            else->Unit
        }
        pendingCopyRelation=null
    }
    fun acceptUpstream() { pkg?.let { repo.acceptUpstream(it)?.let(::open) } }
    fun detachBackup() { pkg?.let { repo.detachUsingBackup(it)?.let(::open) } }
}

@Composable
fun WidgetStudioScreen(repo: WidgetRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val state = remember(repo) { WidgetStudioState(context, repo) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ ${stringResource(R.string.widget_back_to_page)}") }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_studio), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(state.pkg?.manifest?.name ?: state.program.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { state.showLibrary = true }) { Text(stringResource(R.string.widget_library)) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioTabButton(stringResource(R.string.widget_visual), state.tab == WidgetStudioTab.Visual) { state.tab = WidgetStudioTab.Visual }
                    StudioTabButton(stringResource(R.string.widget_code), state.tab == WidgetStudioTab.Code) { state.tab = WidgetStudioTab.Code }
                    StudioTabButton(stringResource(R.string.widget_preview), state.tab == WidgetStudioTab.Preview) { state.tab = WidgetStudioTab.Preview }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = state::compileAndSave) { Text(stringResource(R.string.widget_compile_save)) }
                }
                Text(state.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state.tab) {
            WidgetStudioTab.Visual -> WidgetVisualEditor(state, Modifier.weight(1f).fillMaxWidth())
            WidgetStudioTab.Code -> WidgetCodeEditor(state, Modifier.weight(1f).fillMaxWidth())
            WidgetStudioTab.Preview -> WidgetPreviewEditor(state, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (state.showLibrary) WidgetLibraryDialog(state)
    if (state.pendingCopyRelation != null) WidgetCopyBackupDialog(state)
}

@Composable
private fun StudioTabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(text) } else OutlinedButton(onClick = onClick) { Text(text) }
}

@Composable
private fun WidgetVisualEditor(state: WidgetStudioState, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val p = state.program
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.widget_manifest), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        BufferedStudioField(p.name, stringResource(R.string.widget_name)) { v -> state.applyVisualChange { it.name = v } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericStudioField(p.defaultWidth.toString(), stringResource(R.string.widget_default_width), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { n -> state.applyVisualChange { it.defaultWidth = n.coerceIn(VeilWidgetLimits.MIN_DEFAULT_SIZE, VeilWidgetLimits.MAX_DEFAULT_WIDTH) } } }
            NumericStudioField(p.defaultHeight.toString(), stringResource(R.string.widget_default_height), Modifier.weight(1f)) { v -> v.toIntOrNull()?.let { n -> state.applyVisualChange { it.defaultHeight = n.coerceIn(VeilWidgetLimits.MIN_DEFAULT_SIZE, VeilWidgetLimits.MAX_DEFAULT_HEIGHT) } } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = p.warnOnResize, onCheckedChange = { v -> state.applyVisualChange { it.warnOnResize = v } })
            Text(stringResource(R.string.widget_warn_resize))
        }
        ColorField(stringResource(R.string.widget_background_colour), p.backgroundArgb) { c -> state.applyVisualChange { it.backgroundArgb = c } }
        Text(stringResource(R.string.widget_size_rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.widget_canvas), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        WidgetRuntimeView(program = p, modifier = Modifier.fillMaxWidth().aspectRatio(p.defaultWidth.toFloat() / p.defaultHeight.toFloat()), interactive = false, selectedId = state.selectedNodeId, onSelect = { state.selectedNodeId = it })

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { state.addNode(WidgetNodeType.Text) }) { Text("+ ${stringResource(R.string.widget_text)}") }
            OutlinedButton(onClick = { state.addNode(WidgetNodeType.Button) }) { Text("+ ${stringResource(R.string.widget_button)}") }
            OutlinedButton(onClick = { state.addNode(WidgetNodeType.Box) }) { Text("+ ${stringResource(R.string.widget_box)}") }
            OutlinedButton(onClick = { state.addNode(WidgetNodeType.Stream) }) { Text("+ ${stringResource(R.string.widget_stream)}") }
        }

        val n = state.selectedNode()
        if (n != null) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${stringResource(R.string.widget_selected)}: ${n.id}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = state::deleteSelected) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetFloatSlider(stringResource(R.string.x_position), n.x, Modifier.weight(1f)) { v -> state.applyVisualChange { n.x = v.coerceAtMost(1f - n.width) } }
                WidgetFloatSlider(stringResource(R.string.y_position), n.y, Modifier.weight(1f)) { v -> state.applyVisualChange { n.y = v.coerceAtMost(1f - n.height) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetFloatSlider(stringResource(R.string.width), n.width, Modifier.weight(1f), .05f..1f) { v -> state.applyVisualChange { n.width = v.coerceAtMost(1f - n.x) } }
                WidgetFloatSlider(stringResource(R.string.height), n.height, Modifier.weight(1f), .05f..1f) { v -> state.applyVisualChange { n.height = v.coerceAtMost(1f - n.y) } }
            }
            when (n.type) {
                WidgetNodeType.Text, WidgetNodeType.Button -> {
                    BufferedStudioField(n.text, stringResource(R.string.widget_text_value)) { v -> state.applyVisualChange { n.text = v } }
                    WidgetFloatSlider(stringResource(R.string.font_size), n.fontSize, Modifier.fillMaxWidth(), 8f..72f) { v -> state.applyVisualChange { n.fontSize = v } }
                    ColorField(stringResource(R.string.text_colour), n.textArgb) { c -> state.applyVisualChange { n.textArgb = c } }
                    if (n.type == WidgetNodeType.Button) ColorField(stringResource(R.string.widget_button_background), n.backgroundArgb) { c -> state.applyVisualChange { n.backgroundArgb = c } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n.bold, { v -> state.applyVisualChange { n.bold = v } }); Text(stringResource(R.string.bold));
                        Checkbox(n.italic, { v -> state.applyVisualChange { n.italic = v } }); Text(stringResource(R.string.italic))
                    }
                }
                WidgetNodeType.Box -> {
                    ColorField(stringResource(R.string.widget_box_background), n.backgroundArgb) { c -> state.applyVisualChange { n.backgroundArgb = c } }
                    Text(stringResource(R.string.widget_box_hint), style = MaterialTheme.typography.bodySmall)
                }
                WidgetNodeType.Stream -> {
                    BufferedStudioField(n.streamLabel, stringResource(R.string.widget_stream_label)) { v -> state.applyVisualChange { n.streamLabel = v } }
                    BufferedStudioField(n.streamSource, stringResource(R.string.widget_stream_source)) { v -> state.applyVisualChange { n.streamSource = v } }
                    Text(stringResource(R.string.widget_stream_safety), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.widget_visual_event_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { state.tab = WidgetStudioTab.Code }) { Text(stringResource(R.string.widget_edit_events_code)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BufferedStudioField(value: String, label: String, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    LaunchedEffect(draft) { if (draft != value) { delay(180); onCommit(draft) } }
}

@Composable
private fun NumericStudioField(value: String, label: String, modifier: Modifier, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(value = draft, onValueChange = { draft = it.filter { c -> c.isDigit() } }, label = { Text(label) }, modifier = modifier, singleLine = true)
    LaunchedEffect(draft) { if (draft != value) { delay(250); onCommit(draft) } }
}

@Composable
private fun WidgetFloatSlider(label: String, value: Float, modifier: Modifier, range: ClosedFloatingPointRange<Float> = 0f..1f, onChange: (Float) -> Unit) {
    Column(modifier) {
        Text("$label  ${String.format(Locale.US, "%.2f", value)}", style = MaterialTheme.typography.labelSmall)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun WidgetCodeEditor(state: WidgetStudioState, modifier: Modifier) {
    val modifiedStatus = stringResource(R.string.widget_code_modified)
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.widget_code_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = Color(0xFFFAFBFD),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            BasicTextField(
                value = state.source,
                onValueChange = { state.source = it; state.status = modifiedStatus },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF20242A)),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
        if (state.compileIssues.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    state.compileIssues.take(8).forEach { Text(stringResource(R.string.widget_line_error, it.line, widgetIssueText(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = state::compileCodeOnly) { Text(stringResource(R.string.widget_compile_preview)) }
            OutlinedButton(onClick = state::compileAndSave) { Text(stringResource(R.string.widget_compile_save)) }
        }
    }
}


@Composable
private fun widgetIssueText(issue: WidgetCompileIssue): String = when(issue.code) {
    WidgetIssueCode.SourceTooLarge -> stringResource(R.string.widget_issue_source_too_large, issue.detail)
    WidgetIssueCode.EmptySource -> stringResource(R.string.widget_issue_empty_source)
    WidgetIssueCode.ExpectedHeader -> stringResource(R.string.widget_issue_expected_header)
    WidgetIssueCode.TopLevelIndent -> stringResource(R.string.widget_issue_top_indent)
    WidgetIssueCode.DuplicateId -> stringResource(R.string.widget_issue_duplicate_id, issue.detail)
    WidgetIssueCode.ItemIndent -> stringResource(R.string.widget_issue_item_indent)
    WidgetIssueCode.TimerRange -> stringResource(R.string.widget_issue_timer_range)
    WidgetIssueCode.IntegerExpected -> stringResource(R.string.widget_issue_integer, issue.detail)
    WidgetIssueCode.BoolExpected -> stringResource(R.string.widget_issue_bool, issue.detail)
    WidgetIssueCode.ColorExpected -> stringResource(R.string.widget_issue_colour, issue.detail)
    WidgetIssueCode.UnknownWidgetProperty -> stringResource(R.string.widget_issue_unknown_widget_property, issue.detail)
    WidgetIssueCode.UnknownStatement -> stringResource(R.string.widget_issue_unknown_statement, issue.detail)
    WidgetIssueCode.ExpectedItemAssignment -> stringResource(R.string.widget_issue_item_assignment)
    WidgetIssueCode.QuotedExpected -> stringResource(R.string.widget_issue_quoted, issue.detail)
    WidgetIssueCode.AlignExpected -> stringResource(R.string.widget_issue_align)
    WidgetIssueCode.UnknownNodeProperty -> stringResource(R.string.widget_issue_unknown_node_property, issue.detail)
    WidgetIssueCode.NumberOrPercent -> stringResource(R.string.widget_issue_number_percent, issue.detail)
    WidgetIssueCode.EventIndent -> stringResource(R.string.widget_issue_event_indent)
    WidgetIssueCode.UnsupportedAction -> stringResource(R.string.widget_issue_unsupported_action)
    WidgetIssueCode.DefaultWidthRange -> stringResource(R.string.widget_issue_width_range, issue.detail)
    WidgetIssueCode.DefaultHeightRange -> stringResource(R.string.widget_issue_height_range, issue.detail)
    WidgetIssueCode.TooManyNodes -> stringResource(R.string.widget_issue_too_many_nodes, issue.detail)
    WidgetIssueCode.TooManyEvents -> stringResource(R.string.widget_issue_too_many_events, issue.detail)
    WidgetIssueCode.IdTooLong -> stringResource(R.string.widget_issue_id_too_long, issue.detail)
    WidgetIssueCode.ItemOutsideCanvas -> stringResource(R.string.widget_issue_outside_canvas, issue.detail)
    WidgetIssueCode.ItemTextTooLong -> stringResource(R.string.widget_issue_text_too_long, issue.detail)
    WidgetIssueCode.TooManyActions -> stringResource(R.string.widget_issue_too_many_actions)
    WidgetIssueCode.MissingTapTarget -> stringResource(R.string.widget_issue_missing_tap, issue.detail)
    WidgetIssueCode.MissingActionTarget -> stringResource(R.string.widget_issue_missing_action, issue.detail)
}

@Composable
private fun WidgetPreviewEditor(state: WidgetStudioState, modifier: Modifier) {
    val p = state.compiledProgram
    val capTime = stringResource(R.string.widget_cap_time)
    val capStream = stringResource(R.string.widget_cap_host_stream)
    val relOwn = stringResource(R.string.widget_relation_own)
    val relLink = stringResource(R.string.widget_relation_link)
    val relFork = stringResource(R.string.widget_relation_fork)
    val capabilityText = p.capabilities.joinToString { if (it == WidgetCapability.Time) capTime else capStream }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.widget_runtime_preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.widget_runtime_sandbox), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WidgetRuntimeView(program = p, modifier = Modifier.fillMaxWidth().aspectRatio(p.defaultWidth.toFloat() / p.defaultHeight.toFloat()), interactive = true)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(stringResource(R.string.widget_manifest), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.widget_declared_size, p.defaultWidth, p.defaultHeight))
                Text(stringResource(R.string.widget_resize_warning_value, if (p.warnOnResize) stringResource(R.string.yes) else stringResource(R.string.no)))
                Text(stringResource(R.string.widget_capabilities_value, capabilityText))
                Text(stringResource(R.string.widget_forbidden_apis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        val pkg = state.pkg
        if (pkg != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.widget_provenance), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.widget_hash_value, pkg.manifest.sourceHash.take(20) + "…"), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.widget_relation_value, when(pkg.manifest.relation){WidgetRelation.Own->relOwn;WidgetRelation.Link->relLink;WidgetRelation.Fork->relFork}), style = MaterialTheme.typography.bodySmall)
                    if (pkg.manifest.upstreamWidgetId.isNotBlank()) {
                        Text(stringResource(R.string.widget_origin_hash_value, pkg.manifest.upstreamSourceHash.take(20) + "…"), style = MaterialTheme.typography.bodySmall)
                        if (state.repo.upstreamChanged(pkg)) {
                            Text(stringResource(R.string.widget_upstream_changed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = state::acceptUpstream) { Text(stringResource(R.string.widget_accept_update)) }
                                OutlinedButton(onClick = state::detachBackup) { Text(stringResource(R.string.widget_keep_backup)) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = state::requestLink) { Text(stringResource(R.string.widget_make_link)) }
                        OutlinedButton(onClick = state::requestFork) { Text(stringResource(R.string.widget_make_fork)) }
                    }
                }
            }
        }
    }
}

@Composable
fun WidgetRuntimeView(
    program: WidgetProgram,
    modifier: Modifier,
    interactive: Boolean,
    selectedId: String? = null,
    onSelect: (String) -> Unit = {}
) {
    val textOverrides = remember(program) { mutableStateMapOf<String, String>() }
    val visibleOverrides = remember(program) { mutableStateMapOf<String, Boolean>() }
    val streamPlaying = remember(program) { mutableStateMapOf<String, Boolean>() }

    fun execute(event: WidgetEvent) {
        event.actions.take(VeilWidgetLimits.MAX_INSTRUCTIONS_PER_EVENT).forEach { a ->
            when (a.type) {
                WidgetActionType.SetTextLiteral -> textOverrides[a.targetId] = a.value
                WidgetActionType.SetTextTime -> {
                    val fmt = runCatching { DateTimeFormatter.ofPattern(a.value) }.getOrElse { DateTimeFormatter.ofPattern("HH:mm:ss") }
                    textOverrides[a.targetId] = LocalDateTime.now().format(fmt)
                }
                WidgetActionType.SetVisible -> visibleOverrides[a.targetId] = a.value.equals("true", true)
            }
        }
    }

    program.events.filter { it.trigger == WidgetTriggerType.Every }.forEach { ev ->
        LaunchedEffect(program, ev.targetId, ev.intervalMs, ev.actions.hashCode()) {
            while (true) { delay(ev.intervalMs.coerceAtLeast(VeilWidgetLimits.MIN_TIMER_MS)); execute(ev) }
        }
    }

    BoxWithConstraints(modifier.background(Color(program.backgroundArgb)).clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))) {
        program.nodes.forEach { n ->
            val visible = visibleOverrides[n.id] ?: n.visible
            if (visible) {
                val nodeModifier = Modifier
                    .offset(x = maxWidth * n.x, y = maxHeight * n.y)
                    .size(width = maxWidth * n.width, height = maxHeight * n.height)
                    .then(if (!interactive) Modifier.clickable { onSelect(n.id) } else Modifier)
                    .then(if (selectedId == n.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)) else Modifier)
                when (n.type) {
                    WidgetNodeType.Box -> Box(nodeModifier.background(Color(n.backgroundArgb), RoundedCornerShape(4.dp)))
                    WidgetNodeType.Text -> Box(nodeModifier, contentAlignment = when (n.align) { WidgetTextAlign.Left -> Alignment.CenterStart; WidgetTextAlign.Center -> Alignment.Center; WidgetTextAlign.Right -> Alignment.CenterEnd }) {
                        Text(textOverrides[n.id] ?: n.text, color = Color(n.textArgb), fontSize = n.fontSize.sp, fontWeight = if (n.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (n.italic) FontStyle.Italic else FontStyle.Normal, textDecoration = if (n.underline) TextDecoration.Underline else TextDecoration.None, textAlign = when (n.align) { WidgetTextAlign.Left -> TextAlign.Left; WidgetTextAlign.Center -> TextAlign.Center; WidgetTextAlign.Right -> TextAlign.Right }, modifier = Modifier.fillMaxWidth())
                    }
                    WidgetNodeType.Button -> Button(
                        onClick = { if (interactive) program.events.filter { it.trigger == WidgetTriggerType.Tap && it.targetId == n.id }.forEach(::execute) else onSelect(n.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(n.backgroundArgb), contentColor = Color(n.textArgb)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = nodeModifier
                    ) { Text(textOverrides[n.id] ?: n.text, fontSize = n.fontSize.sp, fontWeight = if (n.bold) FontWeight.Bold else FontWeight.Normal) }
                    WidgetNodeType.Stream -> {
                        val playing = streamPlaying[n.id] == true
                        Surface(color = Color(0xFF20242A), shape = RoundedCornerShape(4.dp), modifier = nodeModifier.clickable(enabled = interactive) { streamPlaying[n.id] = !playing }) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (playing) "■" else "▶", color = Color.White, fontSize = 28.sp)
                                    Text(n.streamLabel, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                    Text(if (playing) stringResource(R.string.widget_mock_stream_playing) else stringResource(R.string.widget_stream_paused), color = Color(0xFFD8DEE6), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
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
private fun WidgetCopyBackupDialog(state: WidgetStudioState) {
    val relation=state.pendingCopyRelation ?: return
    val action=if(relation==WidgetRelation.Link) stringResource(R.string.widget_make_link) else stringResource(R.string.widget_make_fork)
    AlertDialog(
        onDismissRequest={state.pendingCopyRelation=null},
        title={Text(action)},
        text={Text(stringResource(R.string.widget_source_backup_prompt))},
        confirmButton={Button(onClick=state::confirmCopyWithBackup){Text(stringResource(R.string.widget_download_source_backup))}},
        dismissButton={TextButton(onClick={state.pendingCopyRelation=null}){Text(stringResource(R.string.cancel))}}
    )
}

@Composable
private fun WidgetLibraryDialog(state: WidgetStudioState) {
    val all = state.repo.all()
    val relOwn=stringResource(R.string.widget_relation_own);val relLink=stringResource(R.string.widget_relation_link);val relFork=stringResource(R.string.widget_relation_fork)
    AlertDialog(
        onDismissRequest = { state.showLibrary = false },
        title = { Text(stringResource(R.string.widget_library)) },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { state.newWidget(); state.showLibrary = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.widget_new)) }
                all.forEach { pkg ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable { state.open(pkg); state.showLibrary = false }) {
                        Column(Modifier.padding(9.dp)) {
                            Text(pkg.manifest.name, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.widget_library_row, pkg.manifest.defaultWidth, pkg.manifest.defaultHeight, when(pkg.manifest.relation){WidgetRelation.Own->relOwn;WidgetRelation.Link->relLink;WidgetRelation.Fork->relFork}), style = MaterialTheme.typography.labelSmall)
                            Text(pkg.manifest.sourceHash.take(16) + "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { state.showLibrary = false }) { Text(stringResource(R.string.close)) } }
    )
}
