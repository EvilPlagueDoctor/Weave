package app.weave

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var initialLoading by mutableStateOf(true)
        private set
    var operationLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("Loading widget editor…")
        private set

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun selectedNode(): WidgetNode? = program.nodes.firstOrNull { it.id == selectedNodeId }

    private fun applyOpenedPackage(next: WidgetPackage, verifiedProgram: WidgetProgram) {
        pkg = next
        source = next.source
        compileIssues = emptyList()
        program = verifiedProgram
        compiledProgram = verifiedProgram.deepCopy()
        selectedNodeId = program.nodes.firstOrNull()?.id
        status = if (repo.upstreamChanged(next)) context.getString(R.string.widget_upstream_changed) else context.getString(R.string.widget_ready)
        revision++
    }

    /**
     * Repository packages have already been compiled from source and verified by the decoder.
     * Re-parse/compile them again on the Compose thread used to make the 122 KiB Chess template
     * appear to hang the whole editor.  Verify the local bytecode off-main and only publish the
     * finished program to Compose afterwards.
     */
    suspend fun openAsync(next: WidgetPackage) {
        operationLoading = true
        loadingMessage = "Loading ${next.manifest.name}…"
        try {
            val verifiedProgram = withContext(Dispatchers.Default) {
                VeilWidgetVerifier.verifyBytecode(next.bytecode).program
            } ?: makeStarterWidgetProgram()
            applyOpenedPackage(next, verifiedProgram)
        } finally {
            operationLoading = false
        }
    }

    suspend fun loadInitial() {
        if (!initialLoading) return
        val started = System.currentTimeMillis()
        loadingMessage = "Loading widget library…"
        WeaveDiagnostics.event(context, "WIDGET_STUDIO_LOAD", "stage=start")
        try {
            val existing = withContext(Dispatchers.IO) { repo.all().firstOrNull() }
            val first = existing ?: withContext(Dispatchers.IO) { repo.createStarter() }
            openAsync(first)
            WeaveDiagnostics.event(
                context,
                "WIDGET_STUDIO_LOAD",
                "stage=ready name=${first.manifest.name.take(80)} source_bytes=${first.source.toByteArray(Charsets.UTF_8).size} total_ms=${System.currentTimeMillis() - started}"
            )
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            WeaveDiagnostics.event(
                context,
                "WIDGET_STUDIO_LOAD",
                "stage=failed exception=${t::class.java.simpleName}:${t.message.orEmpty().replace('\n', ' ').take(160)} total_ms=${System.currentTimeMillis() - started}"
            )
            // A corrupt/temporarily unavailable library entry must not crash the whole app merely
            // because Studio was opened. Keep the editor usable with an unsaved starter and show
            // the failure in-band so Diagnostics can be copied afterwards.
            val fallback = makeStarterWidgetProgram()
            pkg = null
            program = fallback
            compiledProgram = fallback.deepCopy()
            source = VeilWidgetLanguage.sourceFor(fallback)
            selectedNodeId = fallback.nodes.firstOrNull()?.id
            compileIssues = emptyList()
            status = "Widget library could not be loaded: $message"
            loadingMessage = status
            revision++
        } finally {
            initialLoading = false
        }
    }

    /** Compatibility path for tiny/local operations that already have a package in memory. */
    fun open(next: WidgetPackage) {
        val verifiedProgram = VeilWidgetVerifier.verifyBytecode(next.bytecode).program ?: makeStarterWidgetProgram()
        applyOpenedPackage(next, verifiedProgram)
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

    suspend fun compileAndSaveAsync() {
        operationLoading = true
        loadingMessage = "Compiling and saving ${pkg?.manifest?.name ?: program.name}…"
        try {
            // compileAndSave includes the source compiler and encrypted-vault write; neither
            // belongs on the Compose/UI thread for a 100+ KiB widget.
            val (result, saved) = withContext(Dispatchers.IO) { repo.compileAndSave(source, pkg) }
            compileIssues = result.issues
            if (saved != null && result.program != null) {
                pkg = saved
                program = result.program
                compiledProgram = withContext(Dispatchers.Default) {
                    VeilWidgetVerifier.verifyBytecode(result.bytecode).program ?: result.program.deepCopy()
                }
                selectedNodeId = selectedNodeId?.takeIf { id -> program.nodes.any { it.id == id } } ?: program.nodes.firstOrNull()?.id
                status = context.getString(R.string.widget_compiled_saved)
                toast(status)
                revision++
            } else {
                status = context.getString(R.string.widget_compile_failed)
                tab = WidgetStudioTab.Code
            }
        } finally {
            operationLoading = false
        }
    }

    fun applyVisualChange(block: (WidgetProgram) -> Unit) {
        block(program)
        source = VeilWidgetLanguage.sourceFor(program)
        // Visual Studio controls mutate an already-verified in-memory program through bounded
        // setters.  Recompiling the entire source on every slider tick/key press made a large
        // widget such as Chess allocate hundreds of MB per second.  Preview the trusted mutation
        // immediately; the explicit Compile/Save action remains the full parser+verifier gate.
        compiledProgram = program.deepCopy()
        compileIssues = emptyList()
        status = context.getString(R.string.widget_visual_changed)
        revision++
    }

    suspend fun compileCodeOnlyAsync() {
        operationLoading = true
        loadingMessage = "Compiling preview…"
        try {
            val result = withContext(Dispatchers.Default) { VeilWidgetLanguage.compile(source) }
            compileIssues = result.issues
            if (result.ok && result.program != null) {
                program = result.program
                compiledProgram = withContext(Dispatchers.Default) {
                    VeilWidgetVerifier.verifyBytecode(result.bytecode).program ?: result.program.deepCopy()
                }
                selectedNodeId = program.nodes.firstOrNull()?.id
                status = context.getString(R.string.widget_compile_ok)
                tab = WidgetStudioTab.Preview
                revision++
            } else status = context.getString(R.string.widget_compile_failed)
        } finally {
            operationLoading = false
        }
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
            WidgetNodeType.TextInput -> { node.text = context.getString(R.string.widget_text_input_hint); node.backgroundArgb = 0xFFFFFFFF.toInt(); node.width = .65f; node.height = .18f }
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
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.loadInitial() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state.initialLoading) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(14.dp))
                Text(state.loadingMessage, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Large widgets such as Chess can take a moment to prepare.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
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
                            Button(onClick = { scope.launch { state.compileAndSaveAsync() } }, enabled = !state.operationLoading) { Text(stringResource(R.string.widget_compile_save)) }
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
        }

        if (!state.initialLoading && state.operationLoading) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(state.loadingMessage)
                }
            }
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
            OutlinedButton(onClick = { state.addNode(WidgetNodeType.TextInput) }) { Text("+ ${stringResource(R.string.widget_text_input)}") }
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
                WidgetNodeType.Text, WidgetNodeType.Button, WidgetNodeType.TextInput -> {
                    BufferedStudioField(n.text, stringResource(R.string.widget_text_value)) { v -> state.applyVisualChange { n.text = v } }
                    WidgetFloatSlider(stringResource(R.string.font_size), n.fontSize, Modifier.fillMaxWidth(), 8f..72f) { v -> state.applyVisualChange { n.fontSize = v } }
                    ColorField(stringResource(R.string.text_colour), n.textArgb) { c -> state.applyVisualChange { n.textArgb = c } }
                    if (n.type != WidgetNodeType.Text) ColorField(stringResource(R.string.widget_button_background), n.backgroundArgb) { c -> state.applyVisualChange { n.backgroundArgb = c } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n.bold, { v -> state.applyVisualChange { n.bold = v } }); Text(stringResource(R.string.bold));
                        Checkbox(n.italic, { v -> state.applyVisualChange { n.italic = v } }); Text(stringResource(R.string.italic))
                    }
                }
                WidgetNodeType.Box -> {
                    ColorField(stringResource(R.string.widget_box_background), n.backgroundArgb) { c -> state.applyVisualChange { n.backgroundArgb = c } }
                    Text(stringResource(R.string.widget_box_hint), style = MaterialTheme.typography.bodySmall)
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
    val scope = rememberCoroutineScope()
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
            Button(onClick = { scope.launch { state.compileCodeOnlyAsync() } }, enabled = !state.operationLoading) { Text(stringResource(R.string.widget_compile_preview)) }
            OutlinedButton(onClick = { scope.launch { state.compileAndSaveAsync() } }, enabled = !state.operationLoading) { Text(stringResource(R.string.widget_compile_save)) }
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
    WidgetIssueCode.VerifierRejected -> stringResource(R.string.widget_issue_verifier_rejected, issue.detail)
    WidgetIssueCode.OnlineExpected -> stringResource(R.string.widget_issue_online_expected, issue.detail)
    WidgetIssueCode.TooManyInputs -> stringResource(R.string.widget_issue_too_many_inputs, issue.detail)
    WidgetIssueCode.DuplicateInput -> stringResource(R.string.widget_issue_duplicate_input, issue.detail)
    WidgetIssueCode.InvalidInput -> stringResource(R.string.widget_issue_invalid_input, issue.detail)
    WidgetIssueCode.NetworkRequiresOnline -> stringResource(R.string.widget_issue_network_requires_online)
    WidgetIssueCode.TooManyNetworkInputs -> stringResource(R.string.widget_issue_too_many_network_inputs)
    WidgetIssueCode.NetworkTextTooLong -> stringResource(R.string.widget_issue_network_text_too_long)
    WidgetIssueCode.NetworkTextSourceMissing -> stringResource(R.string.widget_issue_network_text_source_missing, issue.detail)
    WidgetIssueCode.NetworkTimerForbidden -> stringResource(R.string.widget_issue_network_timer_forbidden)
    WidgetIssueCode.TooManyStates -> stringResource(R.string.widget_issue_too_many_states, issue.detail)
    WidgetIssueCode.DuplicateState -> stringResource(R.string.widget_issue_duplicate_state, issue.detail)
    WidgetIssueCode.InvalidState -> stringResource(R.string.widget_issue_invalid_state, issue.detail)
    WidgetIssueCode.InvalidExpression -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.IfDepth -> stringResource(R.string.widget_issue_if_depth, issue.detail)
    WidgetIssueCode.NetworkHandlerInvalid -> stringResource(R.string.widget_issue_network_handler_invalid, issue.detail)
    WidgetIssueCode.InvalidArray -> stringResource(R.string.widget_issue_invalid_state, issue.detail)
    WidgetIssueCode.InvalidFunction -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.DuplicateFunction -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.FunctionDepth -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.InvalidDynamicProperty -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.DynamicTargetInvalid -> stringResource(R.string.widget_issue_invalid_expression, issue.detail)
    WidgetIssueCode.NetworkActionInvalid -> stringResource(R.string.widget_issue_network_handler_invalid, issue.detail)
    WidgetIssueCode.NetworkRandomInvalid -> stringResource(R.string.widget_issue_network_handler_invalid, issue.detail)
}

@Composable
private fun WidgetPreviewEditor(state: WidgetStudioState, modifier: Modifier) {
    val p = state.compiledProgram
    val capTime = stringResource(R.string.widget_cap_time)
    val capNetwork = stringResource(R.string.widget_cap_public_network)
    val relOwn = stringResource(R.string.widget_relation_own)
    val relLink = stringResource(R.string.widget_relation_link)
    val relFork = stringResource(R.string.widget_relation_fork)
    val capabilityText = p.capabilities.joinToString { if (it == WidgetCapability.Time) capTime else capNetwork }
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
                    Text(stringResource(R.string.widget_relation_value, when(pkg.manifest.relation){WidgetRelation.Own->relOwn;WidgetRelation.Link->relLink;WidgetRelation.Fork->relFork;else->relOwn}), style = MaterialTheme.typography.bodySmall)
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

private fun truncateUtf8ForWidget(value: String, maxBytes: Int): String {
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    val out = StringBuilder()
    var used = 0
    var index = 0
    while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        val chunk = String(Character.toChars(codePoint))
        val bytes = chunk.toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        out.append(chunk)
        used += bytes
        index += Character.charCount(codePoint)
    }
    return out.toString()
}

@Composable
fun WidgetRuntimeView(
    program: WidgetProgram,
    modifier: Modifier,
    interactive: Boolean,
    selectedId: String? = null,
    onSelect: (String) -> Unit = {},
    networkHost: WidgetNetworkHost? = null,
) {
    val textOverrides = remember(program) { mutableStateMapOf<String, String>() }
    val visibleOverrides = remember(program) { mutableStateMapOf<String, Boolean>() }
    val enabledOverrides = remember(program) { mutableStateMapOf<String, Boolean>() }
    val backgroundOverrides = remember(program) { mutableStateMapOf<String, Int>() }
    val textColorOverrides = remember(program) { mutableStateMapOf<String, Int>() }
    val textInputValues = remember(program) { mutableStateMapOf<String, String>() }
    var pendingInviteToken by remember(program) { mutableStateOf("") }
    var actionOpenLocal by remember(program) { mutableStateOf(false) }
    var myPlayerMeta by remember(program) { mutableIntStateOf(0) }
    var randomFirstPlayerMeta by remember(program) { mutableIntStateOf(0) }

    val stateValues = remember(program) {
        mutableStateMapOf<String, WidgetValue>().apply {
            program.states.filter { it.kind != WidgetStateKind.NumberArray }.forEach { state ->
                runCatching { WidgetExpressions.literalValue(state.initialValue) }.getOrNull()?.let { put(state.id, it) }
            }
        }
    }
    val arrayValues = remember(program) {
        mutableStateMapOf<String, List<Long>>().apply {
            program.states.filter { it.kind == WidgetStateKind.NumberArray }.forEach { state ->
                val fill = state.initialValue.toLongOrNull() ?: 0L
                put(state.id, List(state.arraySize.coerceIn(1, VeilWidgetLimits.MAX_ARRAY_ITEMS)) { fill })
            }
        }
    }
    val inputDefs = remember(program) { program.inputs.associateBy { it.id } }
    val stateDefs = remember(program) { program.states.associateBy { it.id } }
    val functions = remember(program) { program.functions.associateBy { it.id } }
    val nodeDefs = remember(program) { program.nodes.associateBy { it.id } }

    fun execute(event: WidgetEvent, incoming: WidgetHostEvent? = null) {
        incoming?.let {
            if (it.myPlayer in 1..2) myPlayerMeta = it.myPlayer
            if (it.randomFirstPlayer in 1..2) randomFirstPlayerMeta = it.randomFirstPlayer
        }

        val eventValues = linkedMapOf<String, WidgetValue>()
        eventValues["network.my_player"] = WidgetValue.Number(myPlayerMeta.toLong())
        eventValues["network.event_player"] = WidgetValue.Number((incoming?.eventPlayer ?: 0).toLong())
        eventValues["network.random_first_player"] = WidgetValue.Number(randomFirstPlayerMeta.toLong())
        eventValues["network.roll_index"] = WidgetValue.Number((incoming?.rollIndex ?: 0).toLong())
        eventValues["network.roll_sides"] = WidgetValue.Number((incoming?.rollSides ?: 0).toLong())
        eventValues["network.action_count"] = WidgetValue.Number((incoming?.actionSteps?.size ?: 0).toLong())

        if (incoming != null && (event.trigger == WidgetTriggerType.NetworkInput || event.trigger == WidgetTriggerType.NetworkCommitted)) {
            incoming.inputs.forEach { input ->
                val def = inputDefs[input.inputId] ?: return@forEach
                eventValues[input.inputId] = if (def.kind == WidgetInputKind.Number) WidgetValue.Number(input.numberValue.toLong()) else WidgetValue.BooleanValue(true)
            }
        }
        if (incoming != null && event.trigger == WidgetTriggerType.NetworkText && event.eventVariable.isNotBlank()) {
            eventValues[event.eventVariable] = WidgetValue.Text(incoming.text)
        }
        if (incoming != null && event.trigger == WidgetTriggerType.NetworkRoll && event.eventVariable.isNotBlank()) {
            eventValues[event.eventVariable] = WidgetValue.Number(incoming.rollValue.toLong())
        }

        val eventArrays = linkedMapOf<String, List<Long>>()
        if (incoming != null && (event.trigger == WidgetTriggerType.NetworkAction || event.trigger == WidgetTriggerType.NetworkActionCommitted)) {
            event.networkInputIds.forEach { id ->
                val def = inputDefs[id] ?: return@forEach
                val values = incoming.actionSteps.map { step ->
                    val item = step.firstOrNull { it.inputId == id }
                    when (def.kind) {
                        WidgetInputKind.Number -> item?.numberValue?.toLong() ?: 0L
                        WidgetInputKind.Button -> if (item != null) 1L else 0L
                    }
                }
                eventArrays["network.action.$id"] = values
            }
        }

        fun scalarValues(locals: Map<String, WidgetValue>): Map<String, WidgetValue> = buildMap {
            stateValues.forEach { (k, v) -> put(k, v) }
            putAll(eventValues)
            putAll(locals)
        }
        fun allArrays(): Map<String, List<Long>> = buildMap {
            arrayValues.forEach { (k, v) -> put(k, v) }
            putAll(eventArrays)
        }
        fun evaluate(expr: String, locals: Map<String, WidgetValue> = emptyMap()): WidgetValue? =
            runCatching { WidgetExpressions.evaluate(expr, scalarValues(locals), allArrays()) }.getOrNull()
        fun colorFrom(value: WidgetValue?): Int? {
            val n = (value as? WidgetValue.Number)?.value ?: return null
            if (n !in Int.MIN_VALUE.toLong()..0xffffffffL) return null
            return n.toInt()
        }

        var instructionCount = 0
        val pendingNetworkInputs = mutableListOf<WidgetInputValue>()
        var pendingNetworkText: String? = null

        data class IfFrame(val parentActive: Boolean, val condition: Boolean, var inElse: Boolean = false)

        lateinit var runActions: (List<WidgetAction>, Map<String, WidgetValue>, Int) -> Unit
        runActions = actionRunner@{ actions, locals, callDepth ->
            if (callDepth > VeilWidgetLimits.MAX_CALL_DEPTH) return@actionRunner
            val ifStack = mutableListOf<IfFrame>()
            fun active(): Boolean {
                val frame = ifStack.lastOrNull() ?: return true
                return frame.parentActive && if (frame.inElse) !frame.condition else frame.condition
            }
            actions.forEach actionLoop@{ a ->
                instructionCount++
                if (instructionCount > VeilWidgetLimits.MAX_INSTRUCTIONS_PER_EVENT) return@actionRunner
                when (a.type) {
                    WidgetActionType.IfStart -> {
                        val parent = active()
                        val condition = if (parent) (evaluate(a.value, locals) as? WidgetValue.BooleanValue)?.value == true else false
                        ifStack += IfFrame(parent, condition)
                        return@actionLoop
                    }
                    WidgetActionType.ElseBranch -> { ifStack.lastOrNull()?.inElse = true; return@actionLoop }
                    WidgetActionType.EndIf -> { if (ifStack.isNotEmpty()) ifStack.removeAt(ifStack.lastIndex); return@actionLoop }
                    else -> Unit
                }
                if (!active()) return@actionLoop

                when (a.type) {
                    WidgetActionType.SetTextLiteral -> textOverrides[a.targetId] = a.value
                    WidgetActionType.SetTextTime -> {
                        val fmt = runCatching { DateTimeFormatter.ofPattern(a.value) }.getOrElse { DateTimeFormatter.ofPattern("HH:mm:ss") }
                        textOverrides[a.targetId] = LocalDateTime.now().format(fmt)
                    }
                    WidgetActionType.SetVisible -> visibleOverrides[a.targetId] = a.value.equals("true", true)
                    WidgetActionType.SetTextExpression -> evaluate(a.value, locals)?.let { textOverrides[a.targetId] = truncateUtf8ForWidget(it.displayText(), VeilWidgetLimits.MAX_STRING_BYTES) }
                    WidgetActionType.SetVisibleExpression -> (evaluate(a.value, locals) as? WidgetValue.BooleanValue)?.let { visibleOverrides[a.targetId] = it.value }
                    WidgetActionType.SetEnabledExpression -> (evaluate(a.value, locals) as? WidgetValue.BooleanValue)?.let { enabledOverrides[a.targetId] = it.value }
                    WidgetActionType.SetBackgroundExpression -> colorFrom(evaluate(a.value, locals))?.let { backgroundOverrides[a.targetId] = it }
                    WidgetActionType.SetTextColorExpression -> colorFrom(evaluate(a.value, locals))?.let { textColorOverrides[a.targetId] = it }
                    WidgetActionType.SetState -> {
                        val def = stateDefs[a.targetId]
                        val value = evaluate(a.value, locals)
                        if (def != null && def.kind != WidgetStateKind.NumberArray && value != null && value.kind() == def.kind) {
                            if (value !is WidgetValue.Text || value.value.toByteArray(Charsets.UTF_8).size <= VeilWidgetLimits.MAX_STATE_TEXT_BYTES) stateValues[a.targetId] = value
                        }
                    }
                    WidgetActionType.SetArrayElement -> {
                        val def = stateDefs[a.targetId]
                        if (def?.kind == WidgetStateKind.NumberArray) {
                            val index = (evaluate(a.auxValue, locals) as? WidgetValue.Number)?.value
                            val value = (evaluate(a.value, locals) as? WidgetValue.Number)?.value
                            val current = arrayValues[a.targetId]
                            if (index != null && value != null && current != null && index in 0 until current.size.toLong()) {
                                val copy = current.toMutableList(); copy[index.toInt()] = value; arrayValues[a.targetId] = copy
                            }
                        }
                    }
                    WidgetActionType.CallFunction -> {
                        val fn = functions[a.targetId]
                        if (fn != null && fn.params.size == a.arguments.size && callDepth < VeilWidgetLimits.MAX_CALL_DEPTH) {
                            val params = linkedMapOf<String, WidgetValue>()
                            var ok = true
                            fn.params.zip(a.arguments).forEach { (name, expr) ->
                                val value = evaluate(expr, locals) as? WidgetValue.Number
                                if (value == null) ok = false else params[name] = value
                            }
                            if (ok) runActions(fn.actions, params, callDepth + 1)
                        }
                    }
                    WidgetActionType.SetDynamicTextExpression -> {
                        val target = (evaluate(a.targetId, locals) as? WidgetValue.Text)?.value
                        val value = evaluate(a.value, locals)
                        val node = target?.let(nodeDefs::get)
                        if (target != null && target.length <= 64 && Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(target) && node != null && node.type != WidgetNodeType.Box && value != null) {
                            textOverrides[target] = truncateUtf8ForWidget(value.displayText(), VeilWidgetLimits.MAX_STRING_BYTES)
                        }
                    }
                    WidgetActionType.SetDynamicBackgroundExpression -> {
                        val target = (evaluate(a.targetId, locals) as? WidgetValue.Text)?.value
                        val node = target?.let(nodeDefs::get)
                        if (target != null && target.length <= 64 && Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(target) && node != null) {
                            colorFrom(evaluate(a.value, locals))?.let { backgroundOverrides[target] = it }
                        }
                    }
                    WidgetActionType.NetworkSendInputs -> {
                        val resolved = mutableListOf<WidgetInputValue>()
                        a.networkInputs.forEach { binding ->
                            val def = inputDefs[binding.inputId] ?: return@forEach
                            when (def.kind) {
                                WidgetInputKind.Button -> resolved += WidgetInputValue(binding.inputId, 0)
                                WidgetInputKind.Number -> {
                                    val number = if (binding.numberExpression.isNotBlank()) (evaluate(binding.numberExpression, locals) as? WidgetValue.Number)?.value else binding.numberValue.toLong()
                                    if (number != null && number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && number.toInt() in def.minValue..def.maxValue) resolved += WidgetInputValue(binding.inputId, number.toInt())
                                }
                            }
                        }
                        if (interactive && resolved.isNotEmpty()) {
                            if (actionOpenLocal) networkHost?.send(resolved.take(VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT), "")
                            else pendingNetworkInputs += resolved
                        }
                    }
                    WidgetActionType.NetworkSendTextLiteral -> if (!actionOpenLocal) pendingNetworkText = a.value
                    WidgetActionType.NetworkSendTextFrom -> if (!actionOpenLocal) pendingNetworkText = textInputValues[a.targetId] ?: ""
                    WidgetActionType.NetworkOpenInvitation -> if (interactive) networkHost?.openInvitation()
                    WidgetActionType.NetworkAccept -> if (interactive) incoming?.token?.takeIf { it.isNotBlank() }?.let { networkHost?.accept(it) }
                    WidgetActionType.NetworkReject -> if (interactive) incoming?.token?.takeIf { it.isNotBlank() }?.let { networkHost?.reject(it) }
                    WidgetActionType.NetworkAcceptInvite -> if (interactive) {
                        val token = incoming?.token?.takeIf { it.isNotBlank() } ?: pendingInviteToken.takeIf { it.isNotBlank() }
                        token?.let { networkHost?.acceptInvite(it); pendingInviteToken = "" }
                    }
                    WidgetActionType.NetworkDeclineInvite -> if (interactive) {
                        val token = incoming?.token?.takeIf { it.isNotBlank() } ?: pendingInviteToken.takeIf { it.isNotBlank() }
                        token?.let { networkHost?.declineInvite(it); pendingInviteToken = "" }
                    }
                    WidgetActionType.NetworkBeginAction -> if (interactive && !actionOpenLocal) { networkHost?.beginAction(); actionOpenLocal = true }
                    WidgetActionType.NetworkEndAction -> if (interactive && actionOpenLocal) { networkHost?.endAction(); actionOpenLocal = false }
                    WidgetActionType.NetworkRoll -> if (interactive) a.value.toIntOrNull()?.let { networkHost?.roll(it) }
                    WidgetActionType.IfStart, WidgetActionType.ElseBranch, WidgetActionType.EndIf -> Unit
                }
            }
        }

        runActions(event.actions, emptyMap(), 0)
        if (interactive && !actionOpenLocal && (pendingNetworkInputs.isNotEmpty() || !pendingNetworkText.isNullOrEmpty())) {
            networkHost?.send(pendingNetworkInputs.take(VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT), pendingNetworkText.orEmpty())
        }
    }

    fun dispatchNetwork(incoming: WidgetHostEvent) {
        if (incoming.myPlayer in 1..2) myPlayerMeta = incoming.myPlayer
        if (incoming.randomFirstPlayer in 1..2) randomFirstPlayerMeta = incoming.randomFirstPlayer
        when (incoming.kind) {
            WidgetHostEventKind.Input -> {
                val ids = incoming.inputs.map { it.inputId }.toSet()
                program.events.filter { it.trigger == WidgetTriggerType.NetworkInput && it.networkInputIds.toSet() == ids }.forEach { execute(it, incoming) }
            }
            WidgetHostEventKind.Committed -> {
                val ids = incoming.inputs.map { it.inputId }.toSet()
                program.events.filter { it.trigger == WidgetTriggerType.NetworkCommitted && it.networkInputIds.toSet() == ids }.forEach { execute(it, incoming) }
            }
            WidgetHostEventKind.Action -> {
                val ids = incoming.actionSteps.firstOrNull().orEmpty().map { it.inputId }.toSet()
                program.events.filter { it.trigger == WidgetTriggerType.NetworkAction && it.networkInputIds.toSet() == ids }.forEach { execute(it, incoming) }
            }
            WidgetHostEventKind.ActionCommitted -> {
                val ids = incoming.actionSteps.firstOrNull().orEmpty().map { it.inputId }.toSet()
                program.events.filter { it.trigger == WidgetTriggerType.NetworkActionCommitted && it.networkInputIds.toSet() == ids }.forEach { execute(it, incoming) }
            }
            WidgetHostEventKind.Text -> program.events.filter { it.trigger == WidgetTriggerType.NetworkText }.forEach { execute(it, incoming) }
            WidgetHostEventKind.Invite -> {
                pendingInviteToken = incoming.token
                program.events.filter { it.trigger == WidgetTriggerType.NetworkInvite }.forEach { execute(it, incoming) }
            }
            WidgetHostEventKind.InviteAccepted -> program.events.filter { it.trigger == WidgetTriggerType.NetworkInviteAccepted }.forEach { execute(it, incoming) }
            WidgetHostEventKind.SessionReady -> program.events.filter { it.trigger == WidgetTriggerType.NetworkSessionReady }.forEach { execute(it, incoming) }
            WidgetHostEventKind.RandomReady -> program.events.filter { it.trigger == WidgetTriggerType.NetworkRandomReady }.forEach { execute(it, incoming) }
            WidgetHostEventKind.Roll -> program.events.filter { it.trigger == WidgetTriggerType.NetworkRoll && it.networkRollSides == incoming.rollSides }.forEach { execute(it, incoming) }
        }
    }

    program.events.filter { it.trigger == WidgetTriggerType.Every }.forEach { ev ->
        LaunchedEffect(program, ev.targetId, ev.intervalMs, ev.actions.hashCode()) {
            while (true) { delay(ev.intervalMs.coerceAtLeast(VeilWidgetLimits.MIN_TIMER_MS)); execute(ev) }
        }
    }

    if (interactive && networkHost != null) {
        DisposableEffect(networkHost) { onDispose { networkHost.close() } }
        LaunchedEffect(program, networkHost) {
            while (true) {
                runCatching { networkHost.poll() }.getOrDefault(emptyList()).forEach(::dispatchNetwork)
                delay(1_000L)
            }
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
                val background = backgroundOverrides[n.id] ?: n.backgroundArgb
                val textColor = textColorOverrides[n.id] ?: n.textArgb
                val enabled = enabledOverrides[n.id] ?: true
                when (n.type) {
                    WidgetNodeType.Box -> Box(nodeModifier.background(Color(background), RoundedCornerShape(4.dp)))
                    WidgetNodeType.Text -> Box(nodeModifier, contentAlignment = when (n.align) { WidgetTextAlign.Left -> Alignment.CenterStart; WidgetTextAlign.Center -> Alignment.Center; WidgetTextAlign.Right -> Alignment.CenterEnd }) {
                        Text(textOverrides[n.id] ?: n.text, color = Color(textColor), fontSize = n.fontSize.sp, fontWeight = if (n.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (n.italic) FontStyle.Italic else FontStyle.Normal, textDecoration = if (n.underline) TextDecoration.Underline else TextDecoration.None, textAlign = when (n.align) { WidgetTextAlign.Left -> TextAlign.Left; WidgetTextAlign.Center -> TextAlign.Center; WidgetTextAlign.Right -> TextAlign.Right }, modifier = Modifier.fillMaxWidth())
                    }
                    WidgetNodeType.Button -> Button(
                        onClick = { if (interactive) program.events.filter { it.trigger == WidgetTriggerType.Tap && it.targetId == n.id }.forEach { execute(it) } else onSelect(n.id) },
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(background), contentColor = Color(textColor)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = nodeModifier
                    ) { Text(textOverrides[n.id] ?: n.text, fontSize = n.fontSize.sp, fontWeight = if (n.bold) FontWeight.Bold else FontWeight.Normal) }
                    WidgetNodeType.TextInput -> {
                        val value = textInputValues[n.id] ?: ""
                        OutlinedTextField(
                            value = value,
                            onValueChange = { next -> if (interactive && enabled) textInputValues[n.id] = truncateUtf8ForWidget(next, VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES) },
                            placeholder = { Text(n.text, fontSize = n.fontSize.sp) },
                            singleLine = false,
                            enabled = interactive && enabled,
                            modifier = nodeModifier,
                            textStyle = TextStyle(color = Color(textColor), fontSize = n.fontSize.sp),
                        )
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
    val relOwn=stringResource(R.string.widget_relation_own);val relLink=stringResource(R.string.widget_relation_link);val relFork=stringResource(R.string.widget_relation_fork)
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<WidgetPackage>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        all = runCatching { withContext(Dispatchers.IO) { state.repo.all() } }
            .onFailure { loadError = it.message ?: it::class.java.simpleName }
            .getOrNull()
    }

    AlertDialog(
        onDismissRequest = { state.showLibrary = false },
        title = { Text(stringResource(R.string.widget_library)) },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { state.newWidget(); state.showLibrary = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.widget_new)) }
                when {
                    loadError != null -> Text("Could not load the widget library: $loadError", color = MaterialTheme.colorScheme.error)
                    all == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading widget library…")
                    }
                    else -> all.orEmpty().forEach { pkg ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    state.openAsync(pkg)
                                    state.showLibrary = false
                                }
                            }
                        ) {
                            Column(Modifier.padding(9.dp)) {
                                Text(pkg.manifest.name, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.widget_library_row, pkg.manifest.defaultWidth, pkg.manifest.defaultHeight, when(pkg.manifest.relation){WidgetRelation.Own->relOwn;WidgetRelation.Link->relLink;WidgetRelation.Fork->relFork;else->relOwn}), style = MaterialTheme.typography.labelSmall)
                                Text(pkg.manifest.sourceHash.take(16) + "…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { state.showLibrary = false }) { Text(stringResource(R.string.close)) } }
    )
}
