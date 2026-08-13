package com.veilysocial.profiledesigner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object VeilWidgetLanguage {
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
        program.nodes.forEach { n ->
            appendLine()
            appendLine("    ${n.type.name.lowercase()} ${n.id}:")
            appendLine("        x = ${fmtPct(n.x)}")
            appendLine("        y = ${fmtPct(n.y)}")
            appendLine("        width = ${fmtPct(n.width)}")
            appendLine("        height = ${fmtPct(n.height)}")
            if (!n.visible) appendLine("        visible = false")
            when (n.type) {
                WidgetNodeType.Box -> appendLine("        background = ${quote(colorHex(n.backgroundArgb))}")
                WidgetNodeType.Text, WidgetNodeType.Button -> {
                    appendLine("        text = ${quote(n.text)}")
                    appendLine("        size = ${fmt(n.fontSize)}")
                    appendLine("        color = ${quote(colorHex(n.textArgb))}")
                    if (n.type == WidgetNodeType.Button) appendLine("        background = ${quote(colorHex(n.backgroundArgb))}")
                    if (n.bold) appendLine("        bold = true")
                    if (n.italic) appendLine("        italic = true")
                    if (n.underline) appendLine("        underline = true")
                    appendLine("        align = ${n.align.name.lowercase()}")
                }
                WidgetNodeType.Stream -> {
                    appendLine("        source = ${quote(n.streamSource)}")
                    appendLine("        label = ${quote(n.streamLabel)}")
                    appendLine("        # Streams are always paused when a page first loads.")
                    appendLine("        # TODO(network): host stream routing/playback is supplied by VeilySocial, not by widget code.")
                }
            }
        }
        program.events.forEach { ev ->
            appendLine()
            when (ev.trigger) {
                WidgetTriggerType.Tap -> appendLine("    on tap(${ev.targetId}):")
                WidgetTriggerType.Every -> appendLine("    every ${fmtDuration(ev.intervalMs)}:")
            }
            ev.actions.forEach { a ->
                when (a.type) {
                    WidgetActionType.SetTextLiteral -> appendLine("        ${a.targetId}.text = ${quote(a.value)}")
                    WidgetActionType.SetTextTime -> appendLine("        ${a.targetId}.text = time.format(${quote(a.value)})")
                    WidgetActionType.SetVisible -> appendLine("        ${a.targetId}.visible = ${a.value.lowercase()}")
                }
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

    fun compile(source: String): WidgetCompileResult {
        val issues = mutableListOf<WidgetCompileIssue>()
        if (source.toByteArray(Charsets.UTF_8).size > VeilWidgetLimits.MAX_SOURCE_BYTES) {
            return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(1, WidgetIssueCode.SourceTooLarge, VeilWidgetLimits.MAX_SOURCE_BYTES.toString())))
        }
        val rawLines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
        val lines = rawLines.mapIndexedNotNull { index, raw ->
            val withoutComment = stripComment(raw)
            if (withoutComment.isBlank()) null else ParsedLine(index + 1, raw.takeWhile { it == ' ' }.length, withoutComment.trim())
        }
        if (lines.isEmpty()) return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(1, WidgetIssueCode.EmptySource)))
        val first = lines.first()
        val widgetName = Regex("^widget\\s+\"(.*)\":$").matchEntire(first.text)?.groupValues?.getOrNull(1)
        if (widgetName == null || first.indent != 0) {
            return WidgetCompileResult(false, issues = listOf(WidgetCompileIssue(first.line, WidgetIssueCode.ExpectedHeader)))
        }
        val program = WidgetProgram(name = widgetName)
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent != 4) { issues += WidgetCompileIssue(line.line, WidgetIssueCode.TopLevelIndent); i++; continue }
            val nodeMatch = Regex("^(box|text|button|stream)\\s+([A-Za-z_][A-Za-z0-9_]*):$").matchEntire(line.text)
            val tapMatch = Regex("^on\\s+tap\\(([A-Za-z_][A-Za-z0-9_]*)\\):$").matchEntire(line.text)
            val everyMatch = Regex("^every\\s+(.+):$").matchEntire(line.text)
            when {
                nodeMatch != null -> {
                    val type = when (nodeMatch.groupValues[1]) { "box" -> WidgetNodeType.Box; "text" -> WidgetNodeType.Text; "button" -> WidgetNodeType.Button; else -> WidgetNodeType.Stream }
                    val node = WidgetNode(type = type, id = nodeMatch.groupValues[2], backgroundArgb = if (type == WidgetNodeType.Button) 0xFFF2F3F6.toInt() else 0x00FFFFFF)
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
                tapMatch != null -> {
                    val ev = WidgetEvent(trigger = WidgetTriggerType.Tap, targetId = tapMatch.groupValues[1])
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                everyMatch != null -> {
                    val ms = parseDuration(everyMatch.groupValues[1])
                    if (ms == null || ms !in VeilWidgetLimits.MIN_TIMER_MS..VeilWidgetLimits.MAX_TIMER_MS) {
                        issues += WidgetCompileIssue(line.line, WidgetIssueCode.TimerRange)
                    }
                    val ev = WidgetEvent(trigger = WidgetTriggerType.Every, intervalMs = ms ?: 1000L)
                    i = parseEventBody(lines, i + 1, ev, issues)
                    program.events += ev
                }
                line.text.contains('=') -> {
                    val (key, value) = splitAssignment(line.text)
                    when (key) {
                        "default_width" -> program.defaultWidth = value.toIntOrNull() ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.IntegerExpected, "default_width"); program.defaultWidth }
                        "default_height" -> program.defaultHeight = value.toIntOrNull() ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.IntegerExpected, "default_height"); program.defaultHeight }
                        "warn_on_resize" -> program.warnOnResize = parseBool(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.BoolExpected, "warn_on_resize"); program.warnOnResize }
                        "background" -> program.backgroundArgb = parseColor(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.ColorExpected, "background"); program.backgroundArgb }
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
        return WidgetCompileResult(true, program, bytecode)
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
            "source" -> node.streamSource = unquote(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.QuotedExpected, "source"); node.streamSource }
            "label" -> node.streamLabel = unquote(value) ?: run { issues += WidgetCompileIssue(line.line, WidgetIssueCode.QuotedExpected, "label"); node.streamLabel }
            else -> issues += WidgetCompileIssue(line.line, WidgetIssueCode.UnknownNodeProperty, "${node.type.name.lowercase()}:$key")
        }
    }
    private fun badFloat(line: ParsedLine, key: String, issues: MutableList<WidgetCompileIssue>, fallback: Float): Float { issues += WidgetCompileIssue(line.line, WidgetIssueCode.NumberOrPercent, key); return fallback }

    private fun parseEventBody(lines: List<ParsedLine>, start: Int, ev: WidgetEvent, issues: MutableList<WidgetCompileIssue>): Int {
        var i = start
        while (i < lines.size && lines[i].indent > 4) {
            val p = lines[i]
            if (p.indent != 8) { issues += WidgetCompileIssue(p.line, WidgetIssueCode.EventIndent); i++; continue }
            val textTime = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text\\s*=\\s*time\\.format\\((\".*\")\\)$").matchEntire(p.text)
            val textLiteral = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.text\\s*=\\s*(\".*\")$").matchEntire(p.text)
            val visible = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\.visible\\s*=\\s*(true|false)$", RegexOption.IGNORE_CASE).matchEntire(p.text)
            when {
                textTime != null -> {
                    val format = unquote(textTime.groupValues[2]) ?: "HH:mm:ss"
                    ev.actions += WidgetAction(WidgetActionType.SetTextTime, textTime.groupValues[1], format)
                }
                textLiteral != null -> ev.actions += WidgetAction(WidgetActionType.SetTextLiteral, textLiteral.groupValues[1], unquote(textLiteral.groupValues[2]) ?: "")
                visible != null -> ev.actions += WidgetAction(WidgetActionType.SetVisible, visible.groupValues[1], visible.groupValues[2].lowercase())
                else -> issues += WidgetCompileIssue(p.line, WidgetIssueCode.UnsupportedAction)
            }
            i++
        }
        return i
    }

    private fun parseDuration(s: String): Long? {
        val t = s.trim().lowercase()
        Regex("^(\\d+)\\s*ms$").matchEntire(t)?.let { return it.groupValues[1].toLongOrNull() }
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(second|seconds|s)$").matchEntire(t)?.let { return (it.groupValues[1].toDoubleOrNull()?.times(1000.0))?.roundToInt()?.toLong() }
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(minute|minutes|m)$").matchEntire(t)?.let { return (it.groupValues[1].toDoubleOrNull()?.times(60000.0))?.roundToInt()?.toLong() }
        return null
    }

    private fun validateProgram(p: WidgetProgram, issues: MutableList<WidgetCompileIssue>) {
        if (p.defaultWidth !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_WIDTH) issues += WidgetCompileIssue(1, WidgetIssueCode.DefaultWidthRange, "${VeilWidgetLimits.MIN_DEFAULT_SIZE}..${VeilWidgetLimits.MAX_DEFAULT_WIDTH}")
        if (p.defaultHeight !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_HEIGHT) issues += WidgetCompileIssue(1, WidgetIssueCode.DefaultHeightRange, "${VeilWidgetLimits.MIN_DEFAULT_SIZE}..${VeilWidgetLimits.MAX_DEFAULT_HEIGHT}")
        if (p.nodes.size > VeilWidgetLimits.MAX_NODES) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyNodes, VeilWidgetLimits.MAX_NODES.toString())
        if (p.events.size > VeilWidgetLimits.MAX_EVENTS) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyEvents, VeilWidgetLimits.MAX_EVENTS.toString())
        val ids = p.nodes.map { it.id }.toSet()
        p.nodes.forEach { n ->
            if (n.id.length > 64) issues += WidgetCompileIssue(1, WidgetIssueCode.IdTooLong, n.id.take(16))
            if (n.x !in 0f..1f || n.y !in 0f..1f || n.width <= 0f || n.height <= 0f || n.x + n.width > 1.0001f || n.y + n.height > 1.0001f) issues += WidgetCompileIssue(1, WidgetIssueCode.ItemOutsideCanvas, n.id)
            if (n.text.toByteArray().size > VeilWidgetLimits.MAX_STRING_BYTES || n.streamLabel.toByteArray().size > VeilWidgetLimits.MAX_STRING_BYTES) issues += WidgetCompileIssue(1, WidgetIssueCode.ItemTextTooLong, n.id)
        }
        p.events.forEach { ev ->
            if (ev.actions.size > VeilWidgetLimits.MAX_ACTIONS_PER_EVENT) issues += WidgetCompileIssue(1, WidgetIssueCode.TooManyActions)
            if (ev.trigger == WidgetTriggerType.Tap && ev.targetId !in ids) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingTapTarget, ev.targetId)
            ev.actions.forEach { a -> if (a.targetId !in ids) issues += WidgetCompileIssue(1, WidgetIssueCode.MissingActionTarget, a.targetId) }
        }
        p.capabilities.clear()
        if (p.nodes.any { it.type == WidgetNodeType.Stream }) p.capabilities += WidgetCapability.HostStream
        if (p.events.any { ev -> ev.actions.any { it.type == WidgetActionType.SetTextTime } } || p.events.any { it.trigger == WidgetTriggerType.Every }) p.capabilities += WidgetCapability.Time
        // Deliberately absent in v1: network, camera, microphone, arbitrary DHT, filesystem, process, clipboard, device identifiers.
    }
}

object VeilWidgetBytecode {
    private const val VERSION = 1
    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 255)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { repeat(4) { u8((v ushr (it * 8)).toInt()) } }
        fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)
        fun i64(v: Long) { repeat(8) { u8((v ushr (it * 8)).toInt()) } }
        fun f32(v: Float) { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array(); out.write(b) }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
        fun str(s: String) { val b=s.toByteArray(Charsets.UTF_8); require(b.size<=65535); u16(b.size); out.write(b) }
    }
    private class R(private val b: ByteArray) {
        var p=0
        fun u8():Int { require(p<b.size); return b[p++].toInt() and 255 }
        fun u16()=u8() or (u8() shl 8)
        fun u32():Long { var v=0L; repeat(4){v=v or (u8().toLong() shl (it*8))}; return v }
        fun i32()=u32().toInt()
        fun i64():Long { var v=0L; repeat(8){v=v or (u8().toLong() shl (it*8))}; return v }
        fun f32():Float { require(p+4<=b.size); val v=ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).float;p+=4;return v }
        fun bool()=when(val v=u8()){0->false;1->true;else->error("invalid bool $v")}
        fun str():String { val n=u16();require(p+n<=b.size);val s=b.copyOfRange(p,p+n).toString(Charsets.UTF_8);p+=n;return s }
        fun done()=p==b.size
    }

    fun encode(p: WidgetProgram): ByteArray {
        val w=W(); "VWB1".forEach { w.u8(it.code) }; w.u16(VERSION); w.str(p.name); w.u16(p.defaultWidth); w.u16(p.defaultHeight); w.bool(p.warnOnResize); w.i32(p.backgroundArgb)
        w.u16(p.nodes.size)
        p.nodes.forEach { n ->
            w.u8(n.type.ordinal);w.str(n.id);w.f32(n.x);w.f32(n.y);w.f32(n.width);w.f32(n.height);w.bool(n.visible);w.str(n.text);w.f32(n.fontSize);w.i32(n.textArgb);w.i32(n.backgroundArgb);w.bool(n.bold);w.bool(n.italic);w.bool(n.underline);w.u8(n.align.ordinal);w.str(n.streamSource);w.str(n.streamLabel)
        }
        w.u16(p.events.size)
        p.events.forEach { e ->
            w.u8(e.trigger.ordinal);w.str(e.targetId);w.i64(e.intervalMs);w.u16(e.actions.size)
            e.actions.forEach { a -> w.u8(a.type.ordinal);w.str(a.targetId);w.str(a.value) }
        }
        return w.out.toByteArray()
    }

    fun decode(data: ByteArray): WidgetProgram {
        val r=R(data); require(r.u8()=='V'.code&&r.u8()=='W'.code&&r.u8()=='B'.code&&r.u8()=='1'.code){"not VeilWidget bytecode"}; require(r.u16()==VERSION){"unsupported bytecode version"}
        val p=WidgetProgram(name=r.str(),defaultWidth=r.u16(),defaultHeight=r.u16(),warnOnResize=r.bool(),backgroundArgb=r.i32(),nodes= mutableListOf(),events= mutableListOf())
        val nn=r.u16();require(nn<=VeilWidgetLimits.MAX_NODES);repeat(nn){p.nodes+=WidgetNode(type=WidgetNodeType.entries[r.u8()],id=r.str(),x=r.f32(),y=r.f32(),width=r.f32(),height=r.f32(),visible=r.bool(),text=r.str(),fontSize=r.f32(),textArgb=r.i32(),backgroundArgb=r.i32(),bold=r.bool(),italic=r.bool(),underline=r.bool(),align=WidgetTextAlign.entries[r.u8()],streamSource=r.str(),streamLabel=r.str())}
        val ne=r.u16();require(ne<=VeilWidgetLimits.MAX_EVENTS);repeat(ne){val e=WidgetEvent(trigger=WidgetTriggerType.entries[r.u8()],targetId=r.str(),intervalMs=r.i64(),actions= mutableListOf());val na=r.u16();require(na<=VeilWidgetLimits.MAX_ACTIONS_PER_EVENT);repeat(na){e.actions+=WidgetAction(WidgetActionType.entries[r.u8()],r.str(),r.str())};p.events+=e}
        require(r.done()){"trailing bytecode data"}
        p.capabilities.clear();if(p.nodes.any{it.type==WidgetNodeType.Stream})p.capabilities+=WidgetCapability.HostStream;if(p.events.any{ev->ev.actions.any{it.type==WidgetActionType.SetTextTime}}||p.events.any{it.trigger==WidgetTriggerType.Every})p.capabilities+=WidgetCapability.Time
        return p
    }
}
