package app.weave

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Small source language for Weave widgets.
 *
 * Networking is intentionally not general-purpose networking. `online = public` only unlocks
 * the narrow host operations represented by network.send()/network.text()/network.text_from().
 * The receiving client's verifier and WidgetNetworkManager still validate every value.
 */
object VeilWidgetLanguage {
    // These patterns are deliberately compiled once. Chess is ~3,400 source lines; rebuilding
    // dozens of Regex objects for every action line caused hundreds of MB of temporary allocation
    // and multi-second template preparation on phones.
    private val RE_WIDGET_HEADER = Regex("^widget\\s+(\".*\"):$")
    private val RE_NODE = Regex("^(box|text|button|textinput)\\s+([A-Za-z_][A-Za-z0-9_]*):$")
    private val RE_INPUT_NUMBER = Regex("^input\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*number\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)$")
    private val RE_INPUT_BUTTON = Regex("^input\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*button$")
    private val RE_STATE = Regex("^state\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$")
    private val RE_FUNCTION = Regex("^function\\s+([A-Za-z_][A-Za-z0-9_]*)\\(([^)]*)\\):$")
    private val RE_TAP = Regex("^on\\s+tap\\(([A-Za-z_][A-Za-z0-9_]*)\\):$")
    private val RE_EVERY = Regex("^every\\s+(.+):$")
    private val RE_NETWORK_INPUT = Regex("^on\\s+network\\.input\\(([^)]*)\\):$")
    private val RE_NETWORK_COMMITTED = Regex("^on\\s+network\\.committed\\(([^)]*)\\):$")
    private val RE_NETWORK_ACTION = Regex("^on\\s+network\\.action\\(([^)]*)\\):$")
    private val RE_NETWORK_ACTION_COMMITTED = Regex("^on\\s+network\\.action_committed\\(([^)]*)\\):$")
    private val RE_NETWORK_TEXT_HANDLER = Regex("^on\\s+network\\.text\\(([A-Za-z_][A-Za-z0-9_]*)\\):$")
    private val RE_NETWORK_ROLL_HANDLER = Regex("^on\\s+network\\.roll\\(\\s*(\\d+)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\):$")
    private val RE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")
    private val RE_ARRAY_DECL = Regex("^array\\(\\s*(\\d+)\\s*,\\s*(-?\\d+)\\s*\\)$")
    private val RE_IF = Regex("^if\\s+(.+):$")
    private val RE_TEXT_TIME = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text\\s*=\\s*time\\.format\\((\".*\")\\)$")
    private val RE_TEXT_LITERAL = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text\\s*=\\s*(\".*\")$")
    private val RE_TEXT_EXPRESSION = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text\\s*=\\s*(.+)$")
    private val RE_VISIBLE_LITERAL = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.visible\\s*=\\s*(true|false)$", RegexOption.IGNORE_CASE)
    private val RE_VISIBLE_EXPRESSION = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.visible\\s*=\\s*(.+)$")
    private val RE_ENABLED_EXPRESSION = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.enabled\\s*=\\s*(.+)$")
    private val RE_BACKGROUND_EXPRESSION = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.background\\s*=\\s*(.+)$")
    private val RE_TEXT_COLOR_EXPRESSION = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text_color\\s*=\\s*(.+)$")
    private val RE_ARRAY_ASSIGN = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\[([^]]+)]\\s*=\\s*(.+)$")
    private val RE_CALL = Regex("^call\\s+([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)$")
    private val RE_DYNAMIC_TEXT = Regex("^ui\\.text\\((.*)\\)$")
    private val RE_DYNAMIC_BACKGROUND = Regex("^ui\\.background\\((.*)\\)$")
    private val RE_NETWORK_SEND = Regex("^network\\.send\\((.*)\\)$")
    private val RE_NETWORK_TEXT = Regex("^network\\.text\\((\".*\")\\)$")
    private val RE_NETWORK_TEXT_FROM = Regex("^network\\.text_from\\(([A-Za-z_][A-Za-z0-9_]*)\\)$")
    private val RE_NETWORK_ROLL = Regex("^network\\.roll\\(\\s*(\\d+)\\s*\\)$")
    private val RE_STATE_ASSIGN = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$")
    private val RE_NETWORK_INPUT_VALUE = Regex("^([A-Za-z_][A-Za-z0-9_]*)(?:\\s*=\\s*(.+))?$")
    private val RE_DURATION_MS = Regex("^(\\d+)\\s*ms$")
    private val RE_DURATION_SECONDS = Regex("^(\\d+(?:\\.\\d+)?)\\s*(second|seconds|s)$")
    private val RE_DURATION_MINUTES = Regex("^(\\d+(?:\\.\\d+)?)\\s*(minute|minutes|m)$")
    private fun unquote(value: String): String? {
        val s = value.trim()
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return null
        val body = s.substring(1, s.length - 1)
        val out = StringBuilder()
        var esc = false
        body.forEach { c ->
            if (esc) {
                out.append(when (c) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '"' -> '"'; '\\' -> '\\'; else -> c })
                esc = false
            } else if (c == '\\') esc = true else out.append(c)
        }
        if (esc) out.append('\\')
        return out.toString()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> append(when (c) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> c }) }
        append('"')
    }

    private fun parseBool(v: String): Boolean? = when (v.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
    private fun parsePercent(v: String): Float? {
        val s = v.trim()
        return if (s.endsWith("%")) s.dropLast(1).trim().toFloatOrNull()?.div(100f) else s.toFloatOrNull()
    }
    private fun parseColor(v: String): Int? {
        val s = unquote(v)?.trim() ?: v.trim()
        val h = s.removePrefix("#")
        return try {
            when (h.length) {
                6 -> (0xFF000000L or h.toLong(16)).toInt()
                8 -> h.toLong(16).toInt()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun sourceFor(program: WidgetProgram): String = buildString {
        appendLine("widget ${quote(program.name)}:")
        appendLine("    default_width = ${program.defaultWidth}")
        appendLine("    default_height = ${program.defaultHeight}")
        appendLine("    warn_on_resize = ${program.warnOnResize}")
        appendLine("    background = ${quote(colorHex(program.backgroundArgb))}")
        if (program.onlineMode == WidgetOnlineMode.Public) {
            appendLine("    online = public")
            if (program.mailboxCopy) appendLine("    mail_copy = true")
        }
        program.inputs.forEach { input ->
            when (input.kind) {
                WidgetInputKind.Button -> appendLine("    input ${input.id} = button")
                WidgetInputKind.Number -> appendLine("    input ${input.id} = number(${input.minValue}, ${input.maxValue})")
            }
        }
        program.states.forEach { state ->
            if (state.kind == WidgetStateKind.NumberArray) appendLine("    state ${state.id} = array(${state.arraySize}, ${state.initialValue})")
            else appendLine("    state ${state.id} = ${state.initialValue}")
        }
        program.functions.forEach { fn ->
            appendLine()
            appendLine("    function ${fn.id}(${fn.params.joinToString(", ")}):")
            appendActionsSource(fn.actions, program, baseIndent = 8)
        }
        program.nodes.forEach { n ->
            appendLine()
            val sourceType = when (n.type) {
                WidgetNodeType.Box -> "box"
                WidgetNodeType.Text -> "text"
                WidgetNodeType.Button -> "button"
                WidgetNodeType.TextInput -> "textinput"
            }
            appendLine("    $sourceType ${n.id}:")
            appendLine("        x = ${fmtPct(n.x)}")
            appendLine("        y = ${fmtPct(n.y)}")
            appendLine("        width = ${fmtPct(n.width)}")
            appendLine("        height = ${fmtPct(n.height)}")
            if (!n.visible) appendLine("        visible = false")
            when (n.type) {
                WidgetNodeType.Box -> appendLine("        background = ${quote(colorHex(n.backgroundArgb))}")
                WidgetNodeType.Text, WidgetNodeType.Button, WidgetNodeType.TextInput -> {
                    appendLine("        text = ${quote(n.text)}")
                    appendLine("        size = ${fmt(n.fontSize)}")
                    appendLine("        color = ${quote(colorHex(n.textArgb))}")
                    if (n.type != WidgetNodeType.Text) appendLine("        background = ${quote(colorHex(n.backgroundArgb))}")
                    if (n.bold) appendLine("        bold = true")
                    if (n.italic) appendLine("        italic = true")
                    if (n.underline) appendLine("        underline = true")
                    appendLine("        align = ${n.align.name.lowercase()}")
                }
            }
        }
        program.events.forEach { ev ->
            appendLine()
            when (ev.trigger) {
                WidgetTriggerType.Tap -> appendLine("    on tap(${ev.targetId}):")
                WidgetTriggerType.Every -> appendLine("    every ${fmtDuration(ev.intervalMs)}:")
                WidgetTriggerType.NetworkInput -> appendLine("    on network.input(${ev.networkInputIds.joinToString(", ")}):")
                WidgetTriggerType.NetworkCommitted -> appendLine("    on network.committed(${ev.networkInputIds.joinToString(", ")}):")
                WidgetTriggerType.NetworkText -> appendLine("    on network.text(${ev.eventVariable.ifBlank { "message" }}):")
                WidgetTriggerType.NetworkInvite -> appendLine("    on network.invite:")
                WidgetTriggerType.NetworkInviteAccepted -> appendLine("    on network.invite_accepted:")
                WidgetTriggerType.NetworkSessionReady -> appendLine("    on network.session_ready:")
                WidgetTriggerType.NetworkAction -> appendLine("    on network.action(${ev.networkInputIds.joinToString(", ")}):")
                WidgetTriggerType.NetworkActionCommitted -> appendLine("    on network.action_committed(${ev.networkInputIds.joinToString(", ")}):")
                WidgetTriggerType.NetworkRandomReady -> appendLine("    on network.random_ready:")
                WidgetTriggerType.NetworkRoll -> appendLine("    on network.roll(${ev.networkRollSides}, ${ev.eventVariable.ifBlank { "result" }}):")
            }
            appendActionsSource(ev.actions, program, baseIndent = 8)
        }
    }

    private fun StringBuilder.appendActionsSource(actions: List<WidgetAction>, program: WidgetProgram, baseIndent: Int) {
        var depth = 0
        actions.forEach { a ->
            if (a.type == WidgetActionType.ElseBranch || a.type == WidgetActionType.EndIf) depth = (depth - 1).coerceAtLeast(0)
            val indent = " ".repeat(baseIndent + depth * 4)
            when (a.type) {
                WidgetActionType.SetTextLiteral -> appendLine("${indent}${a.targetId}.text = ${quote(a.value)}")
                WidgetActionType.SetTextTime -> appendLine("${indent}${a.targetId}.text = time.format(${quote(a.value)})")
                WidgetActionType.SetVisible -> appendLine("${indent}${a.targetId}.visible = ${a.value.lowercase()}")
                WidgetActionType.SetTextExpression -> appendLine("${indent}${a.targetId}.text = ${a.value}")
                WidgetActionType.SetVisibleExpression -> appendLine("${indent}${a.targetId}.visible = ${a.value}")
                WidgetActionType.SetEnabledExpression -> appendLine("${indent}${a.targetId}.enabled = ${a.value}")
                WidgetActionType.SetBackgroundExpression -> appendLine("${indent}${a.targetId}.background = ${a.value}")
                WidgetActionType.SetTextColorExpression -> appendLine("${indent}${a.targetId}.text_color = ${a.value}")
                WidgetActionType.SetState -> appendLine("${indent}${a.targetId} = ${a.value}")
                WidgetActionType.SetArrayElement -> appendLine("${indent}${a.targetId}[${a.auxValue}] = ${a.value}")
                WidgetActionType.CallFunction -> appendLine("${indent}call ${a.targetId}(${a.arguments.joinToString(", ")})")
                WidgetActionType.SetDynamicTextExpression -> appendLine("${indent}ui.text(${a.targetId}, ${a.value})")
                WidgetActionType.SetDynamicBackgroundExpression -> appendLine("${indent}ui.background(${a.targetId}, ${a.value})")
                WidgetActionType.IfStart -> { appendLine("${indent}if ${a.value}:"); depth++ }
                WidgetActionType.ElseBranch -> { appendLine("${indent}else:"); depth++ }
                WidgetActionType.EndIf -> Unit
                WidgetActionType.NetworkSendInputs -> {
                    val byId = program.inputs.associateBy { it.id }
                    val body = a.networkInputs.joinToString(", ") { sent -> if (byId[sent.inputId]?.kind == WidgetInputKind.Button) sent.inputId else "${sent.inputId}=${sent.numberExpression.ifBlank { sent.numberValue.toString() }}" }
                    appendLine("${indent}network.send($body)")
                }
                WidgetActionType.NetworkSendTextLiteral -> appendLine("${indent}network.text(${quote(a.value)})")
                WidgetActionType.NetworkSendTextFrom -> appendLine("${indent}network.text_from(${a.targetId})")
                WidgetActionType.NetworkOpenInvitation -> appendLine("${indent}network.invite")
                WidgetActionType.NetworkAccept -> appendLine("${indent}network.accept")
                WidgetActionType.NetworkReject -> appendLine("${indent}network.reject")
                WidgetActionType.NetworkAcceptInvite -> appendLine("${indent}network.accept_invite")
                WidgetActionType.NetworkDeclineInvite -> appendLine("${indent}network.decline_invite")
                WidgetActionType.NetworkBeginAction -> appendLine("${indent}network.begin_action")
                WidgetActionType.NetworkEndAction -> appendLine("${indent}network.end_action")
                WidgetActionType.NetworkRoll -> appendLine("${indent}network.roll(${a.value})")
            }
        }
    }

    private fun fmtPct(v: Float) = "${(v * 1000f).roundToInt() / 10f}%"
    private fun fmt(v: Float): String = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.2f".format(java.util.Locale.US, v)
    private fun fmtDuration(ms: Long): String = when {
        ms % 1000L == 0L -> "${ms / 1000L} seconds"
        else -> "$ms ms"
    }
    fun colorHex(c: Int): String = if ((c ushr 24) == 0xFF) String.format("#%06X", c and 0xFFFFFF) else String.format("#%08X", c)

    fun compile(sourceText: String): WidgetCompileResult {
        val source = canonicalWidgetSource(sourceText)
        val issues = mutableListOf<WidgetCompileIssue>()
        if (source.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_SOURCE_BYTES) {
            return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(1, WidgetIssueCode.SourceTooLarge, VeilWidgetLimits.MAX_SOURCE_BYTES.toString())))
        }
        val rawLines = source.lines()
        val lines = rawLines.mapIndexedNotNull { index, raw ->
            val withoutComment = stripComment(raw)
            if (withoutComment.isBlank()) null else ParsedLine(index + 1, raw.takeWhile { it == ' ' }.length, withoutComment.trim())
        }
        if (lines.isEmpty()) return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(1, WidgetIssueCode.EmptySource)))
        val first = lines.first()
        val widgetNameRaw = RE_WIDGET_HEADER.matchEntire(first.text)?.groupValues?.getOrNull(1)
        val widgetName = widgetNameRaw?.let(::unquote)
        if (widgetName == null || first.indent != 0) {
            return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(first.line, WidgetIssueCode.ExpectedHeader)))
        }
        val program = WidgetProgram(name = widgetName)
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent != 4) { issues += WidgetCompileIssue(line.line, WidgetIssueCode.TopLevelIndent); i++; continue }
            val nodeMatch = RE_NODE.matchEntire(line.text)
            val inputNumber = RE_INPUT_NUMBER.matchEntire(line.text)
            val inputButton = RE_INPUT_BUTTON.matchEntire(line.text)
            val stateMatch = RE_STATE.matchEntire(line.text)
            val functionMatch = RE_FUNCTION.matchEntire(line.text)
            val tapMatch = RE_TAP.matchEntire(line.text)
            val everyMatch = RE_EVERY.matchEntire(line.text)
            val networkInputMatch = RE_NETWORK_INPUT.matchEntire(line.text)
            val networkCommittedMatch = RE_NETWORK_COMMITTED.matchEntire(line.text)
            val networkActionMatch = RE_NETWORK_ACTION.matchEntire(line.text)
            val networkActionCommittedMatch = RE_NETWORK_ACTION_COMMITTED.matchEntire(line.text)
            val networkTextMatch = RE_NETWORK_TEXT_HANDLER.matchEntire(line.text)
            val networkInvite = line.text == "on network.invite:"
            val networkInviteAccepted = line.text == "on network.invite_accepted:"
            val networkSessionReady = line.text == "on network.session_ready:"
            val networkRandomReady = line.text == "on network.random_ready:"
            val networkRollMatch = RE_NETWORK_ROLL_HANDLER.matchEntire(line.text)
            when {
                functionMatch != null -> {
                    val id = functionMatch.groupValues[1]
                    val params = functionMatch.groupValues[2].split(',').map { it.trim() }.filter { it.isNotBlank() }
                    if (params.size > VeilWidgetLimits.MAX_FUNCTION_PARAMS || params.any { !RE_IDENTIFIER.matches(it) } || params.distinct().size != params.size) {
                        issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidFunction, id)
                    }
                    val holder = WidgetEvent()
                    i = parseActionBlock(lines, i + 1, 8, holder, issues, 0)
                    program.functions += WidgetFunction(id, params.toMutableList(), holder.actions)
                }
                nodeMatch != null -> {
                    val type = when (nodeMatch.groupValues[1]) {
                        "box" -> WidgetNodeType.Box
                        "text" -> WidgetNodeType.Text
                        "button" -> WidgetNodeType.Button
                        else -> WidgetNodeType.TextInput
                    }
                    val node = WidgetNode(
                        type = type,
                        id = nodeMatch.groupValues[2],
                        backgroundArgb = when (type) {
                            WidgetNodeType.Button -> 0xFFF2F3F6.toInt()
                            WidgetNodeType.TextInput -> 0xFFFFFFFF.toInt()
                            else -> 0x00FFFFFF
                        }
                    )
                    if (program.nodes.any { it.id == node.id }) issues += WidgetCompileIssue(line.line, WidgetIssueCode.DuplicateId, node.id)
                    i++
                    while (i < lines.size && lines[i].indent > 4) {
                        val p = lines[i]
                        if (p.indent != 8) issues += WidgetCompileIssue(p.line, WidgetIssueCode.ItemIndent)
                        else parseNodeProperty(node, p, issues)
                        i++
                    }
                    program.nodes += node
                }
                inputNumber != null -> {
                    val min = inputNumber.groupValues[2].toIntOrNull()
                    val max = inputNumber.groupValues[3].toIntOrNull()
                    val id = inputNumber.groupValues[1]
                    if (min == null || max == null || min > max) issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidInput, id)
                    else program.inputs += WidgetInputDefinition(id, WidgetInputKind.Number, min, max)
                    i++
                }
                inputButton != null -> {
                    program.inputs += WidgetInputDefinition(inputButton.groupValues[1], WidgetInputKind.Button)
                    i++
                }
                stateMatch != null -> {
                    val id = stateMatch.groupValues[1]
                    val raw = stateMatch.groupValues[2].trim()
                    val definition = parseStateDefinition(id, raw, line, issues)
                    if (definition != null) program.states += definition
                    i++
                }
                tapMatch != null -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.Tap, targetId = tapMatch.groupValues[1])
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                everyMatch != null -> {
                    val ms = parseDuration(everyMatch.groupValues[1])
                    if (ms == null || ms !in VeilWidgetLimits.MIN_TIMER_MS..VeilWidgetLimits.MAX_TIMER_MS) issues += WidgetCompileIssue(line.line, WidgetIssueCode.TimerRange)
                    val ev = WidgetEvent(trigger = WidgetTriggerType.Every, intervalMs = ms ?: 1000L)
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkInputMatch != null -> {
                    val ids = parseNetworkHandlerInputs(networkInputMatch.groupValues[1], line, issues)
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkInput, networkInputIds = ids.toMutableList())
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkCommittedMatch != null -> {
                    val ids = parseNetworkHandlerInputs(networkCommittedMatch.groupValues[1], line, issues)
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkCommitted, networkInputIds = ids.toMutableList())
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkActionMatch != null -> {
                    val ids = parseNetworkHandlerInputs(networkActionMatch.groupValues[1], line, issues)
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkAction, networkInputIds = ids.toMutableList())
                    i = parseEventBody(lines, i + 1, ev, issues); program.events += ev
                }
                networkActionCommittedMatch != null -> {
                    val ids = parseNetworkHandlerInputs(networkActionCommittedMatch.groupValues[1], line, issues)
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkActionCommitted, networkInputIds = ids.toMutableList())
                    i = parseEventBody(lines, i + 1, ev, issues); program.events += ev
                }
                networkTextMatch != null -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkText, eventVariable = networkTextMatch.groupValues[1])
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkInvite -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkInvite)
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkInviteAccepted -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkInviteAccepted)
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkSessionReady -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkSessionReady)
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                networkRandomReady -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkRandomReady)
                    i = parseEventBody(lines, i + 1, ev, issues); program.events += ev
                }
                networkRollMatch != null -> {
                    val sides = networkRollMatch.groupValues[1].toIntOrNull() ?: 0
                    val ev = WidgetEvent(trigger = WidgetTriggerType.NetworkRoll, eventVariable = networkRollMatch.groupValues[2], networkRollSides = sides)
                    i = parseEventBody(lines, i + 1, ev, issues); program.events += ev
                }
                line.text.contains('=') -> {
                    val (key, value) = splitAssignment(line.text)
                    when (key) {
                        "default_width" -> program.defaultWidth = value.toIntOrNull() ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.IntegerExpected, "default_width"); program.defaultWidth }
                        "default_height" -> program.defaultHeight = value.toIntOrNull() ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.IntegerExpected, "default_height"); program.defaultHeight }
                        "warn_on_resize" -> program.warnOnResize = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "warn_on_resize"); program.warnOnResize }
                        "background" -> program.backgroundArgb = parseColor(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.ColorExpected, "background"); program.backgroundArgb }
                        "online" -> program.onlineMode = when (value.trim().lowercase()) {
                            "public" -> WidgetOnlineMode.Public
                            "offline", "none" -> WidgetOnlineMode.Offline
                            else -> { issues += WidgetCompileIssue(line.line, WidgetIssueCode.OnlineExpected, value); program.onlineMode }
                        }
                        "mail_copy" -> program.mailboxCopy = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "mail_copy"); program.mailboxCopy }
                        else -> issues += WidgetCompileIssue(line.line, WidgetIssueCode.UnknownWidgetProperty, key)
                    }
                    i++
                }
                else -> { issues += WidgetCompileIssue(line.line, WidgetIssueCode.UnknownStatement, line.text); i++ }
            }
        }

        validateProgram(program, issues)
        if (issues.isNotEmpty()) return WidgetCompileResult(false, program = program, issues = issues)
        val bytecode = VeilWidgetBytecode.encode(program)
        val verified = VeilWidgetVerifier.verifyBytecode(bytecode)
        if (!verified.ok) {
            val detail = verified.errors.take(3).joinToString("; ")
            return WidgetCompileResult(false, program = program, issues = listOf(WidgetCompileIssue(1, WidgetIssueCode.VerifierRejected, detail)))
        }
        return WidgetCompileResult(true, verified.program ?: program, bytecode)
    }

    private data class ParsedLine(val line: Int, val indent: Int, val text: String)
    private fun stripComment(raw: String): String {
        var quoted = false; var escaped = false
        raw.forEachIndexed { idx, c ->
            if (escaped) { escaped = false; return@forEachIndexed }
            if (c == '\\' && quoted) { escaped = true; return@forEachIndexed }
            if (c == '"') quoted = !quoted
            if (c == '#' && !quoted) return raw.substring(0, idx)
        }
        return raw
    }
    private fun splitAssignment(s: String): Pair<String, String> { val p = s.indexOf('='); return s.substring(0, p).trim() to s.substring(p + 1).trim() }

    private fun parseStateDefinition(id: String, raw: String, line: ParsedLine, issues: MutableList<WidgetCompileIssue>): WidgetStateDefinition? {
        if (id.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_STATE_ID_BYTES) {
            issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidState, "$id is too long")
            return null
        }
        RE_ARRAY_DECL.matchEntire(raw)?.let { match ->
            val size = match.groupValues[1].toIntOrNull() ?: 0
            val fill = match.groupValues[2].toLongOrNull()
            if (size !in 1..VeilWidgetLimits.MAX_ARRAY_ITEMS || fill == null) {
                issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidArray, "$id array size must be 1..${VeilWidgetLimits.MAX_ARRAY_ITEMS}")
                return null
            }
            return WidgetStateDefinition(id, WidgetStateKind.NumberArray, fill.toString(), size)
        }
        unquote(raw)?.let { text ->
            if (text.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_STATE_TEXT_BYTES) {
                issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidState, "$id text is too large")
                return null
            }
            return WidgetStateDefinition(id, WidgetStateKind.Text, quote(text))
        }
        parseBool(raw)?.let { return WidgetStateDefinition(id, WidgetStateKind.Boolean, it.toString()) }
        raw.toLongOrNull()?.let { return WidgetStateDefinition(id, WidgetStateKind.Number, it.toString()) }
        issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidState, "$id must start as an integer, boolean, quoted text, or array(size, fill)")
        return null
    }

    private fun parseNetworkHandlerInputs(body: String, line: ParsedLine, issues: MutableList<WidgetCompileIssue>): List<String> {
        val ids = body.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (ids.isEmpty() || ids.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) {
            issues += WidgetCompileIssue(line.line, WidgetIssueCode.NetworkHandlerInvalid, "network handler needs 1..${VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT} input names")
        }
        ids.forEach { id ->
            if (!RE_IDENTIFIER.matches(id)) issues += WidgetCompileIssue(line.line, WidgetIssueCode.NetworkHandlerInvalid, id.take(32))
        }
        if (ids.distinct().size != ids.size) issues += WidgetCompileIssue(line.line, WidgetIssueCode.NetworkHandlerInvalid, "duplicate input name")
        return ids.take(VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT)
    }

    private fun parseNodeProperty(node: WidgetNode, line: ParsedLine, issues: MutableList<WidgetCompileIssue>) {
        if (!line.text.contains('=')) { issues += WidgetCompileIssue(line.line, WidgetIssueCode.ExpectedItemAssignment); return }
        val (key, value) = splitAssignment(line.text)
        when (key) {
            "x" -> node.x = parsePercent(value) ?: badFloat(line, key, issues, node.x)
            "y" -> node.y = parsePercent(value) ?: badFloat(line, key, issues, node.y)
            "width" -> node.width = parsePercent(value) ?: badFloat(line, key, issues, node.width)
            "height" -> node.height = parsePercent(value) ?: badFloat(line, key, issues, node.height)
            "visible" -> node.visible = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "visible"); node.visible }
            "text" -> node.text = unquote(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.QuotedExpected, "text"); node.text }
            "size" -> node.fontSize = value.toFloatOrNull() ?: badFloat(line, key, issues, node.fontSize)
            "color" -> node.textArgb = parseColor(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.ColorExpected, "color"); node.textArgb }
            "background" -> node.backgroundArgb = parseColor(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.ColorExpected, "background"); node.backgroundArgb }
            "bold" -> node.bold = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "bold"); node.bold }
            "italic" -> node.italic = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "italic"); node.italic }
            "underline" -> node.underline = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "underline"); node.underline }
            "align" -> node.align = when (value.trim().lowercase()) { "left" -> WidgetTextAlign.Left; "center" -> WidgetTextAlign.Center; "right" -> WidgetTextAlign.Right; else -> { issues += WidgetCompileIssue(line.line, WidgetIssueCode.AlignExpected); node.align } }
            else -> issues += WidgetCompileIssue(line.line, WidgetIssueCode.UnknownNodeProperty, "${node.type.name.lowercase()}:$key")
        }
    }
    private fun badFloat(line: ParsedLine, key: String, issues: MutableList<WidgetCompileIssue>, fallback: Float): Float { issues += WidgetCompileIssue(line.line, WidgetIssueCode.NumberOrPercent, key); return fallback }

    private fun parseEventBody(lines: List<ParsedLine>, start: Int, ev: WidgetEvent, issues: MutableList<WidgetCompileIssue>): Int =
        parseActionBlock(lines, start, 8, ev, issues, 0)

    private fun parseActionBlock(
        lines: List<ParsedLine>,
        start: Int,
        indent: Int,
        ev: WidgetEvent,
        issues: MutableList<WidgetCompileIssue>,
        depth: Int,
    ): Int {
        var i = start
        while (i < lines.size) {
            val p = lines[i]
            if (p.indent < indent) break
            if (p.indent > indent) { issues += WidgetCompileIssue(p.line, WidgetIssueCode.EventIndent); i++; continue }
            if (p.text == "else:") break

            val ifMatch = RE_IF.matchEntire(p.text)
            if (ifMatch != null) {
                if (depth >= VeilWidgetLimits.MAX_IF_DEPTH) issues += WidgetCompileIssue(p.line, WidgetIssueCode.IfDepth, VeilWidgetLimits.MAX_IF_DEPTH.toString())
                val expression = ifMatch.groupValues[1].trim()
                ev.actions += WidgetAction(WidgetActionType.IfStart, value = expression)
                i = parseActionBlock(lines, i + 1, indent + 4, ev, issues, depth + 1)
                if (i < lines.size && lines[i].indent == indent && lines[i].text == "else:") {
                    ev.actions += WidgetAction(WidgetActionType.ElseBranch)
                    i = parseActionBlock(lines, i + 1, indent + 4, ev, issues, depth + 1)
                }
                ev.actions += WidgetAction(WidgetActionType.EndIf)
                continue
            }
            parseActionLine(p, ev, issues)
            i++
        }
        return i
    }

    private fun parseActionLine(p: ParsedLine, ev: WidgetEvent, issues: MutableList<WidgetCompileIssue>) {
        val textTime = RE_TEXT_TIME.matchEntire(p.text)
        val textLiteral = RE_TEXT_LITERAL.matchEntire(p.text)
        val textExpression = RE_TEXT_EXPRESSION.matchEntire(p.text)
        val visibleLiteral = RE_VISIBLE_LITERAL.matchEntire(p.text)
        val visibleExpression = RE_VISIBLE_EXPRESSION.matchEntire(p.text)
        val enabledExpression = RE_ENABLED_EXPRESSION.matchEntire(p.text)
        val backgroundExpression = RE_BACKGROUND_EXPRESSION.matchEntire(p.text)
        val textColorExpression = RE_TEXT_COLOR_EXPRESSION.matchEntire(p.text)
        val arrayAssign = RE_ARRAY_ASSIGN.matchEntire(p.text)
        val callMatch = RE_CALL.matchEntire(p.text)
        val dynamicText = RE_DYNAMIC_TEXT.matchEntire(p.text)
        val dynamicBackground = RE_DYNAMIC_BACKGROUND.matchEntire(p.text)
        val networkSend = RE_NETWORK_SEND.matchEntire(p.text)
        val networkText = RE_NETWORK_TEXT.matchEntire(p.text)
        val networkTextFrom = RE_NETWORK_TEXT_FROM.matchEntire(p.text)
        val networkRoll = RE_NETWORK_ROLL.matchEntire(p.text)
        val stateAssign = RE_STATE_ASSIGN.matchEntire(p.text)
        when {
            textTime != null -> {
                val format = unquote(textTime.groupValues[2]) ?: "HH:mm:ss"
                ev.actions += WidgetAction(WidgetActionType.SetTextTime, textTime.groupValues[1], format)
            }
            textLiteral != null -> ev.actions += WidgetAction(WidgetActionType.SetTextLiteral, textLiteral.groupValues[1], unquote(textLiteral.groupValues[2]) ?: "")
            textExpression != null -> ev.actions += WidgetAction(WidgetActionType.SetTextExpression, textExpression.groupValues[1], textExpression.groupValues[2].trim())
            visibleLiteral != null -> ev.actions += WidgetAction(WidgetActionType.SetVisible, visibleLiteral.groupValues[1], visibleLiteral.groupValues[2].lowercase())
            visibleExpression != null -> ev.actions += WidgetAction(WidgetActionType.SetVisibleExpression, visibleExpression.groupValues[1], visibleExpression.groupValues[2].trim())
            enabledExpression != null -> ev.actions += WidgetAction(WidgetActionType.SetEnabledExpression, enabledExpression.groupValues[1], enabledExpression.groupValues[2].trim())
            backgroundExpression != null -> {
                val raw = backgroundExpression.groupValues[2].trim()
                val normalized = parseColor(raw)?.toLong()?.and(0xffffffffL)?.toString() ?: raw
                ev.actions += WidgetAction(WidgetActionType.SetBackgroundExpression, backgroundExpression.groupValues[1], normalized)
            }
            textColorExpression != null -> {
                val raw = textColorExpression.groupValues[2].trim()
                val normalized = parseColor(raw)?.toLong()?.and(0xffffffffL)?.toString() ?: raw
                ev.actions += WidgetAction(WidgetActionType.SetTextColorExpression, textColorExpression.groupValues[1], normalized)
            }
            arrayAssign != null -> ev.actions += WidgetAction(
                WidgetActionType.SetArrayElement,
                targetId = arrayAssign.groupValues[1],
                value = arrayAssign.groupValues[3].trim(),
                auxValue = arrayAssign.groupValues[2].trim(),
            )
            callMatch != null -> {
                val args = splitArguments(callMatch.groupValues[2], p, issues)
                ev.actions += WidgetAction(WidgetActionType.CallFunction, targetId = callMatch.groupValues[1], arguments = args.toMutableList())
            }
            dynamicText != null -> {
                val args = splitArguments(dynamicText.groupValues[1], p, issues)
                if (args.size != 2) issues += WidgetCompileIssue(p.line, WidgetIssueCode.DynamicTargetInvalid, "ui.text expects target and value")
                else ev.actions += WidgetAction(WidgetActionType.SetDynamicTextExpression, targetId = args[0], value = args[1])
            }
            dynamicBackground != null -> {
                val args = splitArguments(dynamicBackground.groupValues[1], p, issues)
                if (args.size != 2) issues += WidgetCompileIssue(p.line, WidgetIssueCode.DynamicTargetInvalid, "ui.background expects target and value")
                else {
                    val raw = args[1].trim()
                    val normalized = parseColor(raw)?.toLong()?.and(0xffffffffL)?.toString() ?: raw
                    ev.actions += WidgetAction(WidgetActionType.SetDynamicBackgroundExpression, targetId = args[0], value = normalized)
                }
            }
            networkSend != null -> {
                val values = parseNetworkInputs(networkSend.groupValues[1], p, issues)
                ev.actions += WidgetAction(WidgetActionType.NetworkSendInputs, networkInputs = values.toMutableList())
            }
            networkText != null -> {
                val text = unquote(networkText.groupValues[1]) ?: ""
                ev.actions += WidgetAction(WidgetActionType.NetworkSendTextLiteral, value = text)
            }
            networkTextFrom != null -> ev.actions += WidgetAction(WidgetActionType.NetworkSendTextFrom, targetId = networkTextFrom.groupValues[1])
            p.text == "network.invite" || p.text == "network.open_invite" -> ev.actions += WidgetAction(WidgetActionType.NetworkOpenInvitation)
            p.text == "network.accept" -> ev.actions += WidgetAction(WidgetActionType.NetworkAccept)
            p.text == "network.reject" -> ev.actions += WidgetAction(WidgetActionType.NetworkReject)
            p.text == "network.accept_invite" -> ev.actions += WidgetAction(WidgetActionType.NetworkAcceptInvite)
            p.text == "network.decline_invite" -> ev.actions += WidgetAction(WidgetActionType.NetworkDeclineInvite)
            p.text == "network.begin_action" -> ev.actions += WidgetAction(WidgetActionType.NetworkBeginAction)
            p.text == "network.end_action" -> ev.actions += WidgetAction(WidgetActionType.NetworkEndAction)
            networkRoll != null -> ev.actions += WidgetAction(WidgetActionType.NetworkRoll, value = networkRoll.groupValues[1])
            stateAssign != null -> ev.actions += WidgetAction(WidgetActionType.SetState, stateAssign.groupValues[1], stateAssign.groupValues[2].trim())
            else -> issues += WidgetCompileIssue(p.line, WidgetIssueCode.UnsupportedAction, p.text.take(80))
        }
    }

    private fun splitArguments(body: String, line: ParsedLine, issues: MutableList<WidgetCompileIssue>): List<String> {
        if (body.isBlank()) return emptyList()
        // V1 expressions contain no comma-bearing constructs, so a strict comma split is canonical.
        val parts = body.split(',').map { it.trim() }
        if (parts.any { it.isBlank() } || parts.size > VeilWidgetLimits.MAX_FUNCTION_PARAMS) {
            issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidFunction, "invalid function argument list")
        }
        return parts.filter { it.isNotBlank() }.take(VeilWidgetLimits.MAX_FUNCTION_PARAMS + 1)
    }

    private fun parseNetworkInputs(body: String, line: ParsedLine, issues: MutableList<WidgetCompileIssue>): List<WidgetInputValue> {
        if (body.isBlank()) {
            issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidInput, "network.send requires at least one input")
            return emptyList()
        }
        val parts = body.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) issues += WidgetCompileIssue(line.line, WidgetIssueCode.TooManyNetworkInputs, VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT.toString())
        return parts.take(VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT + 1).mapNotNull { part ->
            val m = RE_NETWORK_INPUT_VALUE.matchEntire(part)
            if (m == null) {
                issues += WidgetCompileIssue(line.line, WidgetIssueCode.InvalidInput, part.take(32))
                null
            } else {
                val expression = m.groupValues[2].trim()
                val literal = expression.toIntOrNull()
                WidgetInputValue(m.groupValues[1], literal ?: 0, if (expression.isNotBlank() && literal == null) expression else "")
            }
        }
    }

    private fun parseDuration(s: String): Long? {
        val t = s.trim().lowercase()
        RE_DURATION_MS.matchEntire(t)?.let { return it.groupValues[1].toLongOrNull() }
        RE_DURATION_SECONDS.matchEntire(t)?.let { return (it.groupValues[1].toDoubleOrNull()?.times(1000.0))?.roundToInt()?.toLong() }
        RE_DURATION_MINUTES.matchEntire(t)?.let { return (it.groupValues[1].toDoubleOrNull()?.times(60000.0))?.roundToInt()?.toLong() }
        return null
    }

    private fun validateProgram(p: WidgetProgram, issues: MutableList<WidgetCompileIssue>) {
        if (p.defaultWidth !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_WIDTH) issues += WidgetCompileIssue(1, WidgetIssueCode.DefaultWidthRange, "${VeilWidgetLimits.MIN_DEFAULT_SIZE}..${VeilWidgetLimits.MAX_DEFAULT_WIDTH}")
        if (p.defaultHeight !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_HEIGHT) issues += WidgetCompileIssue(1, WidgetIssueCode.DefaultHeightRange, "${VeilWidgetLimits.MIN_DEFAULT_SIZE}..${VeilWidgetLimits.MAX_DEFAULT_HEIGHT}")
        if (p.nodes.size > VeilWidgetLimits.MAX_NODES) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyNodes, VeilWidgetLimits.MAX_NODES.toString())
        if (p.events.size > VeilWidgetLimits.MAX_EVENTS) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyEvents, VeilWidgetLimits.MAX_EVENTS.toString())
        if (p.events.count { it.trigger == WidgetTriggerType.Every } > VeilWidgetLimits.MAX_TIMER_EVENTS) issues += WidgetCompileIssue(1, WidgetIssueCode.VerifierRejected, "maximum ${VeilWidgetLimits.MAX_TIMER_EVENTS} repeating timers")
        if (p.inputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyInputs, VeilWidgetLimits.MAX_NETWORK_INPUTS.toString())
        if (p.states.size > VeilWidgetLimits.MAX_STATES) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyStates, VeilWidgetLimits.MAX_STATES.toString())
        if (p.functions.size > VeilWidgetLimits.MAX_FUNCTIONS) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, "maximum ${VeilWidgetLimits.MAX_FUNCTIONS} functions")
        if (p.mailboxCopy && p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "mail_copy")

        val nodeIds = mutableSetOf<String>()
        val nodesById = linkedMapOf<String, WidgetNode>()
        p.nodes.forEach { n ->
            if (!nodeIds.add(n.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.DuplicateId, n.id)
            nodesById[n.id] = n
            if (n.id.length > 64) issues += WidgetCompileIssue(1, WidgetIssueCode.IdTooLong, n.id.take(16))
            if (n.x !in 0f..1f || n.y !in 0f..1f || n.width <= 0f || n.height <= 0f || n.x + n.width > 1.0001f || n.y + n.height > 1.0001f) issues += WidgetCompileIssue(1, WidgetIssueCode.ItemOutsideCanvas, n.id)
            if (n.text.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_STRING_BYTES) issues += WidgetCompileIssue(1, WidgetIssueCode.ItemTextTooLong, n.id)
            if (!n.fontSize.isFinite() || n.fontSize !in VeilWidgetLimits.MIN_FONT_SIZE..VeilWidgetLimits.MAX_FONT_SIZE) issues += WidgetCompileIssue(1, WidgetIssueCode.VerifierRejected, "font size for ${n.id} must be ${VeilWidgetLimits.MIN_FONT_SIZE.toInt()}..${VeilWidgetLimits.MAX_FONT_SIZE.toInt()}")
        }

        val inputIds = mutableSetOf<String>()
        p.inputs.forEach { input ->
            if (!RE_IDENTIFIER.matches(input.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, input.id.take(32))
            if (!inputIds.add(input.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.DuplicateInput, input.id)
            if (input.kind == WidgetInputKind.Number && input.minValue > input.maxValue) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, input.id)
            if (input.id in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, "input '${input.id}' conflicts with a node id")
        }
        if (p.inputs.isNotEmpty() && p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "input declarations")
        val defs = p.inputs.associateBy { it.id }

        val stateIds = mutableSetOf<String>()
        val stateKinds = linkedMapOf<String, WidgetStateKind>()
        val arrayIds = mutableSetOf<String>()
        p.states.forEach { state ->
            if (!RE_IDENTIFIER.matches(state.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidState, state.id.take(32))
            if (!stateIds.add(state.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.DuplicateState, state.id)
            if (state.id in nodeIds || state.id in inputIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidState, "state '${state.id}' conflicts with another id")
            if (state.kind == WidgetStateKind.NumberArray) {
                if (state.arraySize !in 1..VeilWidgetLimits.MAX_ARRAY_ITEMS || state.initialValue.toLongOrNull() == null) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidArray, state.id)
                arrayIds += state.id
            } else {
                val initial = runCatching { WidgetExpressions.literalValue(state.initialValue) }.getOrNull()
                if (initial == null || initial.kind() != state.kind) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidState, "invalid initial value for ${state.id}")
                if (initial is WidgetValue.Text && initial.value.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_STATE_TEXT_BYTES) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidState, "${state.id} text is too large")
            }
            stateKinds[state.id] = state.kind
        }

        val functionIds = mutableSetOf<String>()
        val functionsById = linkedMapOf<String, WidgetFunction>()
        p.functions.forEach { fn ->
            if (!RE_IDENTIFIER.matches(fn.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, fn.id.take(32))
            if (!functionIds.add(fn.id)) issues += WidgetCompileIssue(1, WidgetIssueCode.DuplicateFunction, fn.id)
            if (fn.id in nodeIds || fn.id in inputIds || fn.id in stateIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, "function '${fn.id}' conflicts with another id")
            if (fn.params.size > VeilWidgetLimits.MAX_FUNCTION_PARAMS || fn.params.distinct().size != fn.params.size) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, fn.id)
            fn.params.forEach { param ->
                if (!RE_IDENTIFIER.matches(param) || param in stateIds || param in nodeIds || param in inputIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, "invalid parameter '$param'")
            }
            if (fn.actions.size > VeilWidgetLimits.MAX_ACTIONS_PER_EVENT) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyActions, fn.id)
            functionsById[fn.id] = fn
        }

        val hostScalarSymbols = mapOf(
            "network.my_player" to WidgetStateKind.Number,
            "network.event_player" to WidgetStateKind.Number,
            "network.random_first_player" to WidgetStateKind.Number,
            "network.roll_index" to WidgetStateKind.Number,
            "network.roll_sides" to WidgetStateKind.Number,
            "network.action_count" to WidgetStateKind.Number,
        )

        fun symbolsFor(ev: WidgetEvent): Pair<Map<String, WidgetStateKind>, Set<String>> {
            val symbols = linkedMapOf<String, WidgetStateKind>()
            symbols.putAll(stateKinds)
            symbols.putAll(hostScalarSymbols)
            val arrays = arrayIds.toMutableSet()
            if (ev.trigger == WidgetTriggerType.NetworkInput || ev.trigger == WidgetTriggerType.NetworkCommitted) {
                ev.networkInputIds.forEach { id ->
                    defs[id]?.let { symbols[id] = if (it.kind == WidgetInputKind.Number) WidgetStateKind.Number else WidgetStateKind.Boolean }
                }
            }
            if (ev.trigger == WidgetTriggerType.NetworkAction || ev.trigger == WidgetTriggerType.NetworkActionCommitted) {
                ev.networkInputIds.forEach { id ->
                    val name = "network.action.$id"
                    symbols[name] = WidgetStateKind.NumberArray
                    arrays += name
                }
            }
            if (ev.trigger == WidgetTriggerType.NetworkText && ev.eventVariable.isNotBlank()) symbols[ev.eventVariable] = WidgetStateKind.Text
            if (ev.trigger == WidgetTriggerType.NetworkRoll && ev.eventVariable.isNotBlank()) symbols[ev.eventVariable] = WidgetStateKind.Number
            return symbols to arrays
        }

        fun checkExpression(expr: String, symbols: Map<String, WidgetStateKind>, arrays: Set<String>, required: WidgetStateKind? = null, label: String) {
            val checked = WidgetExpressions.check(expr, symbols, arrays)
            if (!checked.ok) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "$label: ${checked.error}")
            else if (required != null && checked.kind != required) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "$label must be ${required.name.lowercase()}")
        }

        fun validateActionList(actions: List<WidgetAction>, trigger: WidgetTriggerType?, symbols: Map<String, WidgetStateKind>, arrays: Set<String>, functionContext: Boolean) {
            var ifDepth = 0
            val elseSeen = mutableListOf<Boolean>()
            actions.forEach { a ->
                when (a.type) {
                    WidgetActionType.SetTextLiteral, WidgetActionType.SetTextTime, WidgetActionType.SetVisible -> if (a.targetId !in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingActionTarget, a.targetId)
                    WidgetActionType.SetTextExpression -> {
                        if (a.targetId !in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingActionTarget, a.targetId)
                        checkExpression(a.value, symbols, arrays, null, "${a.targetId}.text")
                    }
                    WidgetActionType.SetVisibleExpression -> {
                        if (a.targetId !in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingActionTarget, a.targetId)
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Boolean, "${a.targetId}.visible")
                    }
                    WidgetActionType.SetEnabledExpression -> {
                        val node = nodesById[a.targetId]
                        if (node == null || node.type !in setOf(WidgetNodeType.Button, WidgetNodeType.TextInput)) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidDynamicProperty, "${a.targetId}.enabled")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Boolean, "${a.targetId}.enabled")
                    }
                    WidgetActionType.SetBackgroundExpression -> {
                        if (a.targetId !in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidDynamicProperty, "${a.targetId}.background")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Number, "${a.targetId}.background")
                    }
                    WidgetActionType.SetTextColorExpression -> {
                        val node = nodesById[a.targetId]
                        if (node == null || node.type == WidgetNodeType.Box) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidDynamicProperty, "${a.targetId}.text_color")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Number, "${a.targetId}.text_color")
                    }
                    WidgetActionType.SetState -> {
                        val expected = stateKinds[a.targetId]
                        if (expected == null || expected == WidgetStateKind.NumberArray) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidState, "unknown/non-scalar state '${a.targetId}'")
                        else checkExpression(a.value, symbols, arrays, expected, "state ${a.targetId}")
                    }
                    WidgetActionType.SetArrayElement -> {
                        if (a.targetId !in arrayIds) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidArray, a.targetId)
                        checkExpression(a.auxValue, symbols, arrays, WidgetStateKind.Number, "${a.targetId} index")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Number, "${a.targetId} value")
                    }
                    WidgetActionType.CallFunction -> {
                        val fn = functionsById[a.targetId]
                        if (fn == null || a.arguments.size != fn.params.size) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidFunction, a.targetId)
                        a.arguments.forEachIndexed { index, expr -> checkExpression(expr, symbols, arrays, WidgetStateKind.Number, "call ${a.targetId} arg ${index + 1}") }
                    }
                    WidgetActionType.SetDynamicTextExpression -> {
                        checkExpression(a.targetId, symbols, arrays, WidgetStateKind.Text, "dynamic UI target")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Text, "dynamic UI text")
                    }
                    WidgetActionType.SetDynamicBackgroundExpression -> {
                        checkExpression(a.targetId, symbols, arrays, WidgetStateKind.Text, "dynamic UI target")
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Number, "dynamic UI background")
                    }
                    WidgetActionType.IfStart -> {
                        checkExpression(a.value, symbols, arrays, WidgetStateKind.Boolean, "if")
                        ifDepth++; elseSeen += false
                        if (ifDepth > VeilWidgetLimits.MAX_IF_DEPTH) issues += WidgetCompileIssue(1, WidgetIssueCode.IfDepth, VeilWidgetLimits.MAX_IF_DEPTH.toString())
                    }
                    WidgetActionType.ElseBranch -> {
                        if (ifDepth <= 0) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "else without if")
                        else { val last = elseSeen.lastIndex; if (elseSeen[last]) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "duplicate else") else elseSeen[last] = true }
                    }
                    WidgetActionType.EndIf -> {
                        if (ifDepth <= 0) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "end-if without if")
                        else { ifDepth--; elseSeen.removeAt(elseSeen.lastIndex) }
                    }
                    WidgetActionType.NetworkSendInputs -> {
                        if (functionContext) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "network.send is not allowed inside helper functions")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.send")
                        if (trigger != WidgetTriggerType.Tap) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTimerForbidden, "network.send is only allowed from a tap event")
                        if (a.networkInputs.isEmpty() || a.networkInputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyNetworkInputs, VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT.toString())
                        val sentIds = mutableSetOf<String>()
                        a.networkInputs.forEach { sent ->
                            val def = defs[sent.inputId]
                            if (def == null || !sentIds.add(sent.inputId)) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, sent.inputId)
                            else when (def.kind) {
                                WidgetInputKind.Number -> if (sent.numberExpression.isNotBlank()) checkExpression(sent.numberExpression, symbols, arrays, WidgetStateKind.Number, "network input ${sent.inputId}") else if (sent.numberValue !in def.minValue..def.maxValue) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, "${sent.inputId}=${sent.numberValue}")
                                WidgetInputKind.Button -> if (sent.numberExpression.isNotBlank() || sent.numberValue != 0) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, sent.inputId)
                            }
                        }
                    }
                    WidgetActionType.NetworkSendTextLiteral -> {
                        if (functionContext) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "network.text is not allowed inside helper functions")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.text")
                        if (trigger != WidgetTriggerType.Tap) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTimerForbidden, "network.text is only allowed from a tap event")
                        if (a.value.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTextTooLong, VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES.toString())
                        runCatching { validateWidgetPlainText(a.value) }.onFailure { issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidInput, "network text contains disallowed control characters") }
                    }
                    WidgetActionType.NetworkSendTextFrom -> {
                        if (functionContext) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "network.text_from is not allowed inside helper functions")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.text_from")
                        if (trigger != WidgetTriggerType.Tap) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTimerForbidden, "network.text_from is only allowed from a tap event")
                        if (p.nodes.firstOrNull { it.id == a.targetId }?.type != WidgetNodeType.TextInput) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTextSourceMissing, a.targetId)
                    }
                    WidgetActionType.NetworkOpenInvitation -> {
                        if (functionContext || trigger != WidgetTriggerType.Tap) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkTimerForbidden, "network.invite is only allowed from a tap event")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.invite")
                    }
                    WidgetActionType.NetworkAccept, WidgetActionType.NetworkReject -> {
                        if (functionContext || trigger !in setOf(WidgetTriggerType.NetworkInput, WidgetTriggerType.NetworkAction)) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "network.accept/reject is only valid inside input/action proposals")
                    }
                    WidgetActionType.NetworkAcceptInvite, WidgetActionType.NetworkDeclineInvite -> {
                        if (functionContext || trigger !in setOf(WidgetTriggerType.NetworkInvite, WidgetTriggerType.Tap)) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "invite decision is only valid inside an invite/tap handler")
                    }
                    WidgetActionType.NetworkBeginAction, WidgetActionType.NetworkEndAction -> {
                        if (functionContext || trigger != WidgetTriggerType.Tap) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkActionInvalid, "grouped actions may only be controlled by taps")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "grouped action")
                    }
                    WidgetActionType.NetworkRoll -> {
                        if (functionContext || trigger !in setOf(WidgetTriggerType.Tap, WidgetTriggerType.NetworkRandomReady)) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRandomInvalid, "network.roll is only allowed from tap/random_ready")
                        val sides = a.value.toIntOrNull()
                        if (sides == null || sides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRandomInvalid, "dice sides must be 2..${VeilWidgetLimits.MAX_DICE_SIDES}")
                        if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.roll")
                    }
                }
            }
            if (ifDepth != 0) issues += WidgetCompileIssue(1, WidgetIssueCode.InvalidExpression, "unclosed if block")
        }

        p.functions.forEach { fn ->
            val symbols = linkedMapOf<String, WidgetStateKind>()
            symbols.putAll(stateKinds)
            fn.params.forEach { symbols[it] = WidgetStateKind.Number }
            validateActionList(fn.actions, null, symbols, arrayIds, functionContext = true)
        }

        // Reject recursion/cycles so runtime call depth is only a corruption backstop.
        val graph = p.functions.associate { fn -> fn.id to fn.actions.filter { it.type == WidgetActionType.CallFunction }.map { it.targetId } }
        fun hasCycle(start: String, node: String, path: MutableSet<String>): Boolean {
            if (!path.add(node)) return node == start
            val result = graph[node].orEmpty().any { next -> next == start || (next in graph && hasCycle(start, next, path.toMutableSet())) }
            return result
        }
        p.functions.forEach { fn -> if (hasCycle(fn.id, fn.id, mutableSetOf())) issues += WidgetCompileIssue(1, WidgetIssueCode.FunctionDepth, "recursive function cycle at ${fn.id}") }

        p.events.forEach { ev ->
            if (ev.actions.size > VeilWidgetLimits.MAX_ACTIONS_PER_EVENT) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyActions)
            when (ev.trigger) {
                WidgetTriggerType.Tap -> if (ev.targetId !in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingTapTarget, ev.targetId)
                WidgetTriggerType.Every -> Unit
                WidgetTriggerType.NetworkInput, WidgetTriggerType.NetworkCommitted, WidgetTriggerType.NetworkAction, WidgetTriggerType.NetworkActionCommitted -> {
                    if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, ev.trigger.name)
                    if (ev.networkInputIds.isEmpty() || ev.networkInputIds.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT || ev.networkInputIds.distinct().size != ev.networkInputIds.size) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "invalid input handler signature")
                    ev.networkInputIds.forEach { if (it !in defs) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "undeclared input '$it'") }
                }
                WidgetTriggerType.NetworkText -> {
                    if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.text handler")
                    if (!RE_IDENTIFIER.matches(ev.eventVariable) || ev.eventVariable in stateIds || ev.eventVariable in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkHandlerInvalid, "invalid text variable")
                }
                WidgetTriggerType.NetworkInvite, WidgetTriggerType.NetworkInviteAccepted, WidgetTriggerType.NetworkSessionReady, WidgetTriggerType.NetworkRandomReady -> {
                    if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, ev.trigger.name)
                }
                WidgetTriggerType.NetworkRoll -> {
                    if (p.onlineMode != WidgetOnlineMode.Public) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRequiresOnline, "network.roll handler")
                    if (ev.networkRollSides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRandomInvalid, "invalid roll sides")
                    if (!RE_IDENTIFIER.matches(ev.eventVariable) || ev.eventVariable in stateIds || ev.eventVariable in nodeIds) issues += WidgetCompileIssue(1, WidgetIssueCode.NetworkRandomInvalid, "invalid roll result variable")
                }
            }
            val (symbols, arrays) = symbolsFor(ev)
            validateActionList(ev.actions, ev.trigger, symbols, arrays, functionContext = false)
        }

        p.capabilities.clear()
        if (p.onlineMode == WidgetOnlineMode.Public) p.capabilities += WidgetCapability.PublicNetwork
        if (p.events.any { ev -> ev.actions.any { it.type == WidgetActionType.SetTextTime } } || p.functions.any { fn -> fn.actions.any { it.type == WidgetActionType.SetTextTime } } || p.events.any { it.trigger == WidgetTriggerType.Every }) p.capabilities += WidgetCapability.Time
        // Deliberately absent: raw sockets/HTTP, arbitrary DHT keys, camera/mic, filesystem,
        // process/native execution, clipboard, installed-font enumeration, high-resolution timers.
    }

}

/** Deterministic local bytecode. It is never published or trusted from another client. */
object VeilWidgetBytecode {
    private const val VERSION = 5
    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 255)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { repeat(4) { u8((v ushr (it * 8)).toInt()) } }
        fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)
        fun i64(v: Long) { repeat(8) { u8((v ushr (it * 8)).toInt()) } }
        fun f32(v: Float) { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array(); out.write(b) }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); require(b.size <= 65535); u16(b.size); out.write(b) }
    }
    private class R(private val b: ByteArray) {
        var p = 0
        fun u8(): Int { require(p < b.size); return b[p++].toInt() and 255 }
        fun u16() = u8() or (u8() shl 8)
        fun u32(): Long { var v = 0L; repeat(4) { v = v or (u8().toLong() shl (it * 8)) }; return v }
        fun i32() = u32().toInt()
        fun i64(): Long { var v = 0L; repeat(8) { v = v or (u8().toLong() shl (it * 8)) }; return v }
        fun f32(): Float { require(p + 4 <= b.size); val v = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).float; p += 4; return v }
        fun bool() = when (val v = u8()) { 0 -> false; 1 -> true; else -> error("invalid bool $v") }
        fun str(): String { val n = u16(); require(p + n <= b.size); val s = b.copyOfRange(p, p + n).toString(Charsets.UTF_8); p += n; return s }
        fun done() = p == b.size
    }

    private fun writeAction(w: W, a: WidgetAction) {
        w.u8(a.type.ordinal); w.str(a.targetId); w.str(a.value); w.str(a.auxValue)
        w.u8(a.networkInputs.size)
        a.networkInputs.forEach { v -> w.str(v.inputId); w.i32(v.numberValue); w.str(v.numberExpression) }
        w.u8(a.arguments.size); a.arguments.forEach(w::str)
    }

    private fun readAction(r: R): WidgetAction {
        val type = r.u8(); require(type in WidgetActionType.entries.indices)
        val action = WidgetAction(type = WidgetActionType.entries[type], targetId = r.str(), value = r.str(), auxValue = r.str())
        val nv = r.u8(); require(nv <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT)
        repeat(nv) { action.networkInputs += WidgetInputValue(r.str(), r.i32(), r.str()) }
        val na = r.u8(); require(na <= VeilWidgetLimits.MAX_FUNCTION_PARAMS)
        repeat(na) { action.arguments += r.str() }
        return action
    }

    fun encode(p: WidgetProgram): ByteArray {
        val w = W(); "VWB5".forEach { w.u8(it.code) }; w.u16(VERSION)
        w.str(p.name); w.u16(p.defaultWidth); w.u16(p.defaultHeight); w.bool(p.warnOnResize); w.i32(p.backgroundArgb)
        w.u8(p.onlineMode.ordinal); w.bool(p.mailboxCopy)
        w.u8(p.inputs.size)
        p.inputs.forEach { input -> w.str(input.id); w.u8(input.kind.ordinal); w.i32(input.minValue); w.i32(input.maxValue) }
        w.u8(p.states.size)
        p.states.forEach { state -> w.str(state.id); w.u8(state.kind.ordinal); w.str(state.initialValue); w.u16(state.arraySize) }
        w.u8(p.functions.size)
        p.functions.forEach { fn ->
            w.str(fn.id); w.u8(fn.params.size); fn.params.forEach(w::str)
            w.u16(fn.actions.size); fn.actions.forEach { writeAction(w, it) }
        }
        w.u16(p.nodes.size)
        p.nodes.forEach { n ->
            w.u8(n.type.ordinal); w.str(n.id); w.f32(n.x); w.f32(n.y); w.f32(n.width); w.f32(n.height); w.bool(n.visible); w.str(n.text); w.f32(n.fontSize); w.i32(n.textArgb); w.i32(n.backgroundArgb); w.bool(n.bold); w.bool(n.italic); w.bool(n.underline); w.u8(n.align.ordinal)
        }
        w.u16(p.events.size)
        p.events.forEach { e ->
            w.u8(e.trigger.ordinal); w.str(e.targetId); w.i64(e.intervalMs)
            w.u8(e.networkInputIds.size); e.networkInputIds.forEach(w::str); w.str(e.eventVariable); w.i32(e.networkRollSides)
            w.u16(e.actions.size); e.actions.forEach { writeAction(w, it) }
        }
        return w.out.toByteArray()
    }

    fun decode(data: ByteArray): WidgetProgram {
        val r = R(data)
        require(r.u8() == 'V'.code && r.u8() == 'W'.code && r.u8() == 'B'.code && r.u8() == '5'.code) { "not VeilWidget v5 bytecode" }
        require(r.u16() == VERSION) { "unsupported bytecode version" }
        val name = r.str(); val width = r.u16(); val height = r.u16(); val warn = r.bool(); val bg = r.i32()
        val om = r.u8(); require(om in WidgetOnlineMode.entries.indices)
        val p = WidgetProgram(name = name, defaultWidth = width, defaultHeight = height, warnOnResize = warn, backgroundArgb = bg, onlineMode = WidgetOnlineMode.entries[om], mailboxCopy = r.bool())
        val ni = r.u8(); require(ni <= VeilWidgetLimits.MAX_NETWORK_INPUTS)
        repeat(ni) { val id = r.str(); val kind = r.u8(); require(kind in WidgetInputKind.entries.indices); p.inputs += WidgetInputDefinition(id, WidgetInputKind.entries[kind], r.i32(), r.i32()) }
        val ns = r.u8(); require(ns <= VeilWidgetLimits.MAX_STATES)
        repeat(ns) { val id = r.str(); val kind = r.u8(); require(kind in WidgetStateKind.entries.indices); p.states += WidgetStateDefinition(id, WidgetStateKind.entries[kind], r.str(), r.u16()) }
        val nf = r.u8(); require(nf <= VeilWidgetLimits.MAX_FUNCTIONS)
        repeat(nf) {
            val fn = WidgetFunction(id = r.str())
            val np = r.u8(); require(np <= VeilWidgetLimits.MAX_FUNCTION_PARAMS); repeat(np) { fn.params += r.str() }
            val na = r.u16(); require(na <= VeilWidgetLimits.MAX_ACTIONS_PER_EVENT); repeat(na) { fn.actions += readAction(r) }
            p.functions += fn
        }
        val nn = r.u16(); require(nn <= VeilWidgetLimits.MAX_NODES)
        repeat(nn) {
            val type = r.u8(); require(type in WidgetNodeType.entries.indices)
            val id = r.str(); val x = r.f32(); val y = r.f32(); val widthN = r.f32(); val heightN = r.f32(); val visible = r.bool(); val text = r.str(); val fontSize = r.f32(); val textArgb = r.i32(); val backgroundArgb = r.i32(); val bold = r.bool(); val italic = r.bool(); val underline = r.bool()
            val align = r.u8(); require(align in WidgetTextAlign.entries.indices)
            p.nodes += WidgetNode(type = WidgetNodeType.entries[type], id = id, x = x, y = y, width = widthN, height = heightN, visible = visible, text = text, fontSize = fontSize, textArgb = textArgb, backgroundArgb = backgroundArgb, bold = bold, italic = italic, underline = underline, align = WidgetTextAlign.entries[align])
        }
        val ne = r.u16(); require(ne <= VeilWidgetLimits.MAX_EVENTS)
        repeat(ne) {
            val trigger = r.u8(); require(trigger in WidgetTriggerType.entries.indices)
            val e = WidgetEvent(trigger = WidgetTriggerType.entries[trigger], targetId = r.str(), intervalMs = r.i64())
            val nids = r.u8(); require(nids <= VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT); repeat(nids) { e.networkInputIds += r.str() }
            e.eventVariable = r.str(); e.networkRollSides = r.i32()
            val na = r.u16(); require(na <= VeilWidgetLimits.MAX_ACTIONS_PER_EVENT); repeat(na) { e.actions += readAction(r) }
            p.events += e
        }
        require(r.done()) { "trailing bytecode data" }
        p.capabilities.clear()
        if (p.onlineMode == WidgetOnlineMode.Public) p.capabilities += WidgetCapability.PublicNetwork
        if (p.events.any { it.trigger == WidgetTriggerType.Every || it.actions.any { a -> a.type == WidgetActionType.SetTextTime } } || p.functions.any { fn -> fn.actions.any { it.type == WidgetActionType.SetTextTime } }) p.capabilities += WidgetCapability.Time
        return p
    }
}
