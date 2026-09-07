package app.weave

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

object ProfileCodec {
    private const val ENVELOPE = "VEILYSOCIAL_PROFILE_V2"
    private const val LEGACY_ENVELOPE = "VEILYSOCIAL_PROFILE_V1"

    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v and 0xFF) }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { repeat(4) { u8((v ushr (it * 8)).toInt()) } }
        fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun f32(v: Float) = u32(java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFFFFFFL)
        fun str(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            require(b.size <= VspfLimits.MAX_STRING_BYTES) { "string exceeds VSPF limit" }
            u32(b.size.toLong()); out.write(b)
        }
    }

    private class R(private val data: ByteArray) {
        var pos = 0
        private fun need(n: Int) { require(pos + n <= data.size) { "truncated VSPF payload" } }
        fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
        fun bool(): Boolean { val v = u8(); require(v <= 1) { "invalid bool" }; return v != 0 }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long { var v = 0L; repeat(4) { v = v or (u8().toLong() shl (it * 8)) }; return v }
        fun i32(): Int = u32().toInt()
        fun f32(): Float = java.lang.Float.intBitsToFloat(u32().toInt())
        fun str(): String {
            val n = u32().toInt(); require(n in 0..VspfLimits.MAX_STRING_BYTES) { "string exceeds VSPF limit" }
            need(n); val s = String(data, pos, n, StandardCharsets.UTF_8); pos += n; return s
        }
        fun done() = pos == data.size
    }

    data class Validation(val ok: Boolean, val message: String = "")

    fun validate(doc: ProfileDocument): Validation {
        if (doc.pages.isEmpty()) return Validation(false, "profile has no pages")
        if (doc.pages.size > VspfLimits.MAX_PAGES) return Validation(false, "too many pages")
        if (doc.pages.none { it.id == doc.defaultPageId }) return Validation(false, "default page id does not exist")
        for (p in doc.pages) {
            if (p.aspectRatio !in .20f..1.20f) return Validation(false, "page aspect ratio outside 0.20..1.20")
            var count = 0
            fun walk(e: Element, depth: Int): String? {
                if (depth > VspfLimits.MAX_DEPTH) return "nesting exceeds limit"
                count++; if (count > VspfLimits.MAX_ELEMENTS_PER_PAGE) return "page element limit exceeded"
                val q = e.rect
                if (q.x !in 0f..1f || q.y !in 0f..1f || q.width !in .0001f..1f || q.height !in .0001f..1f) return "element rectangle outside normalized range"
                if (e.background.stops.size > VspfLimits.MAX_GRADIENT_STOPS) return "too many gradient stops"
                if (e.background.kind == BackgroundKind.LinearGradient && e.background.stops.size < 2) return "gradient needs at least two stops"
                if (e.background.stops.any { it.position !in 0f..1f }) return "gradient stop outside 0..1"
                if (e.opacity !in 0f..1f) return "stamp opacity outside 0..1"
                e.children.forEach { child -> walk(child, depth + 1)?.let { return it } }
                return null
            }
            walk(p.root, 0)?.let { return Validation(false, it) }
        }
        return Validation(true)
    }

    private fun writeDecoration(w: W, d: DecorationRef) {
        w.u8(d.kind.ordinal); w.str(d.builtinName); w.str(d.packRecordKey); w.u32(d.itemId); w.str(d.contentHash); w.str(d.basedOnBuiltin)
    }
    private fun readDecoration(r: R) = DecorationRef(
        kind = DecorationKind.entries[r.u8()], builtinName = r.str(), packRecordKey = r.str(), itemId = r.u32(), contentHash = r.str(), basedOnBuiltin = r.str()
    )
    private fun writeBackground(w: W, b: BackgroundSpec) {
        w.u8(b.kind.ordinal); w.u32(b.solidArgb.toLong() and 0xFFFFFFFFL); w.f32(b.startX); w.f32(b.startY); w.f32(b.endX); w.f32(b.endY)
        require(b.stops.size <= VspfLimits.MAX_GRADIENT_STOPS); w.u8(b.stops.size)
        b.stops.forEach { w.f32(it.position); w.u32(it.argb.toLong() and 0xFFFFFFFFL) }
    }
    private fun readBackground(r: R): BackgroundSpec {
        val b = BackgroundSpec(kind = BackgroundKind.entries[r.u8()], solidArgb = r.u32().toInt(), startX = r.f32(), startY = r.f32(), endX = r.f32(), endY = r.f32(), stops = mutableListOf())
        val n = r.u8(); require(n <= VspfLimits.MAX_GRADIENT_STOPS) { "too many gradient stops" }
        repeat(n) { b.stops += GradientStop(r.f32(), r.u32().toInt()) }
        return b
    }
    private fun writeRect(w: W, q: RectSpec) { w.f32(q.x); w.f32(q.y); w.f32(q.width); w.f32(q.height); w.i32(q.zIndex); w.bool(q.visible) }
    private fun readRect(r: R) = RectSpec(r.f32(), r.f32(), r.f32(), r.f32(), r.i32(), r.bool())

    private fun writeElement(w: W, e: Element, depth: Int) {
        require(depth <= VspfLimits.MAX_DEPTH)
        w.u8(e.type.ordinal + 1); w.str(e.id); w.str(e.name); writeRect(w, e.rect)
        when (e.type) {
            ElementType.Block -> {
                w.u8(e.layout.ordinal); writeBackground(w, e.background); writeDecoration(w, e.border); w.f32(e.borderThickness); w.bool(e.clipChildren); w.bool(e.scrollChildren); w.u8(e.sticky.ordinal)
                require(e.children.size <= 65535); w.u16(e.children.size); e.children.forEach { writeElement(w, it, depth + 1) }
            }
            ElementType.Text -> { w.str(e.text); w.str(e.fontId); w.f32(e.fontSize); w.u32(e.textArgb.toLong() and 0xFFFFFFFFL); w.u8(e.textAlign.ordinal); w.bool(e.bold); w.bool(e.italic); w.bool(e.underline) }
            ElementType.Link -> { w.str(e.label); w.u8(e.targetType.ordinal); w.str(e.target) }
            ElementType.Button -> { w.str(e.label); writeDecoration(w, e.buttonDecoration); writeBackground(w, e.background); w.str(e.fontId); w.f32(e.fontSize); w.u32(e.textArgb.toLong() and 0xFFFFFFFFL); w.u8(e.textAlign.ordinal); w.bool(e.bold); w.bool(e.italic); w.bool(e.underline); w.u8(e.targetType.ordinal); w.str(e.target) }
            ElementType.Stamp -> { writeDecoration(w, e.stampDecoration); w.f32(e.rotationDegrees); w.f32(e.opacity); w.bool(e.flipX); w.bool(e.flipY) }
            ElementType.Media -> { w.u8(e.mediaKind.ordinal); w.str(e.mediaRecordKey); w.str(e.mediaContentHash); w.u32(e.intrinsicWidth); w.u32(e.intrinsicHeight); w.str(e.mediaTitle); w.str(e.mediaDescription) }
            ElementType.Widget -> { w.str(e.widgetLabel); w.str(e.widgetRecordKey); w.u32(e.widgetItemId); w.str(e.widgetSourceHash); w.u32(e.widgetDefaultWidth); w.u32(e.widgetDefaultHeight); w.bool(e.widgetWarnOnResize) }
        }
    }

    private fun readElement(r: R, depth: Int, count: IntArray, version: Int): Element {
        require(depth <= VspfLimits.MAX_DEPTH) { "element nesting too deep" }
        count[0]++; require(count[0] <= VspfLimits.MAX_ELEMENTS_PER_PAGE) { "too many elements" }
        val tag = r.u8(); require(tag in 1..7) { "unknown VSPF element type" }
        val e = Element(type = ElementType.entries[tag - 1], id = r.str(), name = r.str(), rect = readRect(r))
        when (e.type) {
            ElementType.Block -> {
                e.layout = LayoutMode.entries[r.u8()]; e.background = readBackground(r); e.border = readDecoration(r); e.borderThickness = r.f32(); e.clipChildren = r.bool(); e.scrollChildren = r.bool(); e.sticky = StickyMode.entries[r.u8()]
                val n = r.u16(); e.children.clear(); repeat(n) { e.children += readElement(r, depth + 1, count, version) }
            }
            ElementType.Text -> {
                e.text = r.str()
                if (version >= 2) e.fontId = r.str()
                e.fontSize = r.f32(); e.textArgb = r.u32().toInt(); e.textAlign = TextAlignMode.entries[r.u8()]; e.bold = r.bool(); e.italic = r.bool()
                if (version >= 2) e.underline = r.bool()
            }
            ElementType.Link -> { e.label = r.str(); e.targetType = LinkTargetType.entries[r.u8()]; e.target = r.str() }
            ElementType.Button -> {
                e.label = r.str(); e.buttonDecoration = readDecoration(r)
                if (version >= 2) {
                    e.background = readBackground(r); e.fontId = r.str(); e.fontSize = r.f32(); e.textArgb = r.u32().toInt(); e.textAlign = TextAlignMode.entries[r.u8()]; e.bold = r.bool(); e.italic = r.bool(); e.underline = r.bool()
                } else {
                    e.background = BackgroundSpec(solidArgb = 0xFFECF0F8.toInt()); e.fontId = "Default1"; e.fontSize = 16f; e.textArgb = 0xFF111827.toInt(); e.textAlign = TextAlignMode.Center
                }
                e.targetType = LinkTargetType.entries[r.u8()]; e.target = r.str()
            }
            ElementType.Stamp -> { e.stampDecoration = readDecoration(r); e.rotationDegrees = r.f32(); e.opacity = r.f32(); e.flipX = r.bool(); e.flipY = r.bool() }
            ElementType.Media -> { e.mediaKind = MediaKind.entries[r.u8()]; e.mediaRecordKey = r.str(); e.mediaContentHash = r.str(); e.intrinsicWidth = r.u32(); e.intrinsicHeight = r.u32(); e.mediaTitle = r.str(); e.mediaDescription = r.str() }
            ElementType.Widget -> { e.widgetLabel = r.str(); e.widgetRecordKey = r.str(); e.widgetItemId = r.u32(); if (version >= 3) { e.widgetSourceHash = r.str(); e.widgetDefaultWidth = r.u32(); e.widgetDefaultHeight = r.u32(); e.widgetWarnOnResize = r.bool() } }
        }
        return e
    }

    fun encodeBinary(doc: ProfileDocument): ByteArray {
        val v = validate(doc); require(v.ok) { v.message }
        val w = W(); "VSPF".forEach { w.u8(it.code) }; w.u16(VspfLimits.FORMAT_VERSION); w.str(doc.profileId); w.str(doc.profileName); w.str(doc.defaultPageId); w.u16(doc.pages.size)
        doc.pages.forEach { p -> w.str(p.id); w.str(p.name); w.f32(p.aspectRatio); writeElement(w, p.root, 0) }
        return w.out.toByteArray()
    }

    fun decodeBinary(data: ByteArray): ProfileDocument {
        val r = R(data); require(r.u8() == 'V'.code && r.u8() == 'S'.code && r.u8() == 'P'.code && r.u8() == 'F'.code) { "not a VSPF profile" }
        val version = r.u16(); require(version in 1..VspfLimits.FORMAT_VERSION) { "unsupported VSPF version" }
        val doc = ProfileDocument(profileId = r.str(), profileName = r.str(), defaultPageId = r.str(), pages = mutableListOf())
        val n = r.u16(); require(n in 1..VspfLimits.MAX_PAGES) { "invalid page count" }
        repeat(n) {
            val id = r.str(); val name = r.str(); val aspect = r.f32(); require(aspect in .20f..1.20f) { "invalid page aspect ratio" }
            doc.pages += Page(id = id, name = name, aspectRatio = aspect, root = readElement(r, 0, intArrayOf(0), version))
        }
        require(r.done()) { "trailing data in VSPF payload" }
        val v = validate(doc); require(v.ok) { v.message }; return doc
    }

    fun encodeText(doc: ProfileDocument): String = ENVELOPE + "\n" + Base64.getEncoder().encodeToString(encodeBinary(doc)) + "\n"
    fun decodeText(text: String): ProfileDocument {
        val first = text.lineSequence().firstOrNull() ?: error("empty profile file")
        require(first == ENVELOPE || first == LEGACY_ENVELOPE) { "invalid profile text envelope" }
        val b64 = text.substringAfter('\n').filterNot { it.isWhitespace() }
        return decodeBinary(Base64.getDecoder().decode(b64))
    }
    fun save(doc: ProfileDocument, file: File) { file.parentFile?.mkdirs(); file.writeText(encodeText(doc), Charsets.UTF_8) }
    fun load(file: File): ProfileDocument = decodeText(file.readText(Charsets.UTF_8))
}
