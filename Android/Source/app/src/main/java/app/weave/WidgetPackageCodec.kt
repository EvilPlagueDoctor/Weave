package app.weave

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Source-first widget package codec.
 *
 * SECURITY CONTRACT
 * -----------------
 * V2 packages contain source + manifest/provenance only. They never contain executable
 * widget bytecode. On every decode, this client:
 *   1) canonicalizes and hashes the received source,
 *   2) checks the pinned source hash,
 *   3) compiles the source with the local compiler,
 *   4) independently verifies the locally-produced bytecode,
 *   5) derives executable capabilities from that verified program.
 *
 * A modified publisher therefore cannot smuggle custom bytecode/opcodes into an ordinary
 * Weave client. A modified *viewer* can of course weaken its own local runtime, but that
 * does not alter the rules enforced by other clients.
 */
object WidgetPackageCodec {
    private const val LOCAL_ENVELOPE_V2 = "WEAVE_WIDGET_LOCAL_SOURCE_PACKAGE_V2"
    private const val PUBLISHED_ENVELOPE_V1 = "WEAVE_WIDGET_SOURCE_PACKAGE_V1"
    private const val LEGACY_ENVELOPE_V1 = "VEILYSOCIAL_WIDGET_PACKAGE_TEXT_V1"
    private const val SOURCE_PACKAGE_VERSION = 2
    private const val LEGACY_PACKAGE_VERSION = 1

    private val widgetIdPattern = Regex("^[A-Za-z0-9_.-]{1,96}$")

    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 255)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { repeat(4) { u8((v ushr (it * 8)).toInt()) } }
        fun i64(v: Long) { repeat(8) { u8((v ushr (it * 8)).toInt()) } }
        fun bool(v: Boolean) = u8(if (v) 1 else 0)
        fun str(s: String) { val b=s.toByteArray(Charsets.UTF_8); require(b.size<=65535); u16(b.size); out.write(b) }
        fun bytes(b: ByteArray) { u32(b.size.toLong()); out.write(b) }
    }

    private class R(private val b: ByteArray) {
        var p = 0
        fun u8(): Int { require(p < b.size); return b[p++].toInt() and 255 }
        fun u16() = u8() or (u8() shl 8)
        fun u32(): Long { var v=0L; repeat(4){v=v or (u8().toLong() shl (it*8))}; return v }
        fun i64(): Long { var v=0L; repeat(8){v=v or (u8().toLong() shl (it*8))}; return v }
        fun bool() = when (val v=u8()) { 0 -> false; 1 -> true; else -> error("invalid bool $v") }
        fun str(): String { val n=u16(); require(p+n<=b.size); val s=b.copyOfRange(p,p+n).toString(Charsets.UTF_8); p+=n; return s }
        fun bytes(): ByteArray { val n=u32().toInt(); require(n>=0&&p+n<=b.size); return b.copyOfRange(p,p+n).also{p+=n} }
        fun done() = p == b.size
    }

    /** Local encrypted-vault representation. Source-only; bytecode is rebuilt on load. */
    fun encodeText(pkg: WidgetPackage): String = LOCAL_ENVELOPE_V2 + "\n" +
        Base64.getEncoder().encodeToString(encodeSourceBinary(pkg, includeLocalBackup = true)) + "\n"

    /**
     * Fast local-vault encoder for a package that was compiled by this client in memory.
     *
     * The vault format is still source-only. We verify the already-produced local bytecode and
     * source hash, then use that verified program only to fill the descriptive manifest fields.
     * On every later decode the source is compiled and verified again, so no executable bytecode
     * is persisted or trusted across launches. Network/publication export deliberately does NOT
     * use this shortcut.
     */
    /**
     * Internal fast path for a WidgetProgram returned by VeilWidgetLanguage.compile(), which has
     * already passed the bytecode verifier. This still writes source only; it merely avoids doing
     * the expensive Chess verifier pass a second time immediately after compilation.
     */
    fun encodePreparedLocalText(pkg: WidgetPackage, program: WidgetProgram): String {
        val canonicalSource = canonicalWidgetSource(pkg.source)
        val sourceHash = widgetSourceHash(canonicalSource)
        require(pkg.manifest.sourceHash == sourceHash) { "local widget source hash is stale" }
        require(program.name == pkg.manifest.name) { "local widget name does not match compiled program" }
        require(program.defaultWidth == pkg.manifest.defaultWidth && program.defaultHeight == pkg.manifest.defaultHeight) { "local widget size does not match compiled program" }
        require(program.warnOnResize == pkg.manifest.warnOnResize) { "local widget resize declaration does not match compiled program" }
        require(program.capabilities == pkg.manifest.capabilities) { "local widget capabilities do not match compiled program" }
        return LOCAL_ENVELOPE_V2 + "\n" +
            Base64.getEncoder().encodeToString(encodeSourceBinary(pkg, includeLocalBackup = true, preverifiedProgram = program)) + "\n"
    }

    fun encodeVerifiedLocalText(pkg: WidgetPackage): String {
        val canonicalSource = canonicalWidgetSource(pkg.source)
        val sourceHash = widgetSourceHash(canonicalSource)
        require(pkg.manifest.sourceHash == sourceHash) { "local widget source hash is stale" }
        val verified = VeilWidgetVerifier.verifyBytecode(pkg.bytecode)
        require(verified.ok && verified.program != null) { "local widget bytecode failed verifier" }
        val p = verified.program!!
        require(p.name == pkg.manifest.name) { "local widget name does not match compiled program" }
        require(p.defaultWidth == pkg.manifest.defaultWidth && p.defaultHeight == pkg.manifest.defaultHeight) { "local widget size does not match compiled program" }
        require(p.warnOnResize == pkg.manifest.warnOnResize) { "local widget resize declaration does not match compiled program" }
        require(p.capabilities == pkg.manifest.capabilities) { "local widget capabilities do not match compiled program" }
        return LOCAL_ENVELOPE_V2 + "\n" +
            Base64.getEncoder().encodeToString(encodeSourceBinary(pkg, includeLocalBackup = true, preverifiedProgram = p)) + "\n"
    }

    /** Future/network publication representation. Source-only and strips local backup state. */
    fun encodePublishedText(pkg: WidgetPackage): String = PUBLISHED_ENVELOPE_V1 + "\n" +
        Base64.getEncoder().encodeToString(encodeSourceBinary(pkg, includeLocalBackup = false)) + "\n"

    fun decodeText(text: String): WidgetPackage {
        val first = text.lineSequence().firstOrNull() ?: error("empty widget file")
        val b64 = text.substringAfter('\n').filterNot { it.isWhitespace() }
        require(b64.isNotBlank()) { "empty widget package payload" }
        val bytes = Base64.getDecoder().decode(b64)
        return when (first) {
            LOCAL_ENVELOPE_V2, PUBLISHED_ENVELOPE_V1 -> decodeSourceBinary(bytes)
            LEGACY_ENVELOPE_V1 -> decodeLegacyBinary(bytes)
            else -> error("invalid widget text envelope")
        }
    }

    fun isSourceOnlyEnvelope(text: String): Boolean {
        val first = text.lineSequence().firstOrNull() ?: return false
        return first == LOCAL_ENVELOPE_V2 || first == PUBLISHED_ENVELOPE_V1
    }

    private fun encodeSourceBinary(pkg: WidgetPackage, includeLocalBackup: Boolean, preverifiedProgram: WidgetProgram? = null): ByteArray {
        val canonicalSource = canonicalWidgetSource(pkg.source)
        val p = preverifiedProgram ?: run {
            val compiled = VeilWidgetLanguage.compile(canonicalSource)
            require(compiled.ok && compiled.program != null) { "widget source does not compile" }
            val verified = VeilWidgetVerifier.verifyBytecode(compiled.bytecode)
            require(verified.ok) { "widget compiler output failed verifier: ${verified.errors.joinToString()}" }
            verified.program!!
        }

        // Manifest execution-relevant fields are always derived from source before export.
        val sourceHash = widgetSourceHash(canonicalSource)
        val m = pkg.manifest
        requireValidMetadata(m)

        val w = W()
        "WSPK".forEach { w.u8(it.code) }
        w.u16(SOURCE_PACKAGE_VERSION)
        w.str(m.widgetId)
        w.str(m.author.take(512))
        w.u16(VeilWidgetLimits.RUNTIME_VERSION)
        w.u8(m.relation.ordinal)
        w.str(m.upstreamWidgetId)
        w.str(m.upstreamSourceHash)
        w.bytes(if (includeLocalBackup) canonicalWidgetSource(m.localBackupSource).toByteArray(Charsets.UTF_8) else byteArrayOf())
        w.str(sourceHash)

        // These fields are descriptive duplicates. Decoder cross-checks/re-derives them.
        w.str(p.name)
        w.u16(p.defaultWidth)
        w.u16(p.defaultHeight)
        w.bool(p.warnOnResize)
        w.u8(p.capabilities.size)
        p.capabilities.sortedBy { it.ordinal }.forEach { w.u8(it.ordinal) }

        w.bytes(canonicalSource.toByteArray(Charsets.UTF_8))
        w.i64(pkg.modifiedEpochMs)
        return w.out.toByteArray()
    }

    private fun decodeSourceBinary(data: ByteArray): WidgetPackage {
        val r = R(data)
        require(r.u8()=='W'.code && r.u8()=='S'.code && r.u8()=='P'.code && r.u8()=='K'.code) { "not a Weave source widget package" }
        require(r.u16()==SOURCE_PACKAGE_VERSION) { "unsupported source widget package version" }

        val widgetId = r.str()
        val author = r.str()
        val runtimeVersion = r.u16()
        val relationOrdinal = r.u8()
        require(relationOrdinal in WidgetRelation.entries.indices) { "invalid widget relation" }
        val relation = WidgetRelation.entries[relationOrdinal]
        val upstreamWidgetId = r.str()
        val upstreamSourceHash = r.str()
        val localBackup = r.bytes().toString(Charsets.UTF_8)
        val pinnedSourceHash = r.str()

        val declaredName = r.str()
        val declaredWidth = r.u16()
        val declaredHeight = r.u16()
        val declaredWarn = r.bool()
        val declaredCapabilities = mutableSetOf<WidgetCapability>()
        var legacyRemovedCapability = false
        repeat(r.u8()) {
            val ordinal = r.u8()
            if (runtimeVersion == 1) {
                // Runtime-v1 ordinal 1 represented a capability removed from runtime v2.
                // Never reinterpret an old ordinal as the new PublicNetwork capability.
                when (ordinal) {
                    0 -> declaredCapabilities += WidgetCapability.Time
                    1 -> legacyRemovedCapability = true
                    else -> error("invalid legacy widget capability")
                }
            } else {
                require(ordinal in WidgetCapability.entries.indices) { "invalid widget capability" }
                declaredCapabilities += WidgetCapability.entries[ordinal]
            }
        }

        val sourceBytes = r.bytes()
        require(sourceBytes.size <= VeilWidgetLimits.MAX_SOURCE_BYTES) { "widget source too large" }
        val source = canonicalWidgetSource(sourceBytes.toString(Charsets.UTF_8))
        val modified = r.i64()
        require(r.done()) { "trailing widget package data" }
        require(runtimeVersion in 1..VeilWidgetLimits.RUNTIME_VERSION) { "unsupported widget runtime version $runtimeVersion" }
        require(!legacyRemovedCapability) { "This widget uses a capability removed from the current sandbox. Edit its source before importing it." }

        val manifestForValidation = WidgetManifest(
            widgetId = widgetId,
            name = declaredName,
            author = author,
            runtimeVersion = VeilWidgetLimits.RUNTIME_VERSION,
            defaultWidth = declaredWidth,
            defaultHeight = declaredHeight,
            warnOnResize = declaredWarn,
            capabilities = declaredCapabilities,
            sourceHash = pinnedSourceHash,
            relation = relation,
            upstreamWidgetId = upstreamWidgetId,
            upstreamSourceHash = upstreamSourceHash,
            localBackupSource = canonicalWidgetSource(localBackup)
        )
        requireValidMetadata(manifestForValidation)

        val calculatedHash = widgetSourceHash(source)
        require(calculatedHash == pinnedSourceHash) { "widget source hash does not match package manifest" }

        // Crucially, there is NO executable bytecode in the received package.
        val compiled = VeilWidgetLanguage.compile(source)
        require(compiled.ok && compiled.program != null) { "widget source does not compile locally" }
        val verified = VeilWidgetVerifier.verifyBytecode(compiled.bytecode)
        require(verified.ok && verified.program != null) { "locally compiled widget failed bytecode verification" }
        val p = verified.program

        // Detect misleading/tampered descriptive manifest fields. Capabilities can never be
        // hidden by changing metadata; they are derived from locally compiled code.
        require(declaredName == p.name) { "widget name does not match source" }
        require(declaredWidth == p.defaultWidth && declaredHeight == p.defaultHeight) { "widget size does not match source" }
        require(declaredWarn == p.warnOnResize) { "widget resize declaration does not match source" }
        if (runtimeVersion >= 2) require(declaredCapabilities == p.capabilities) { "widget capabilities do not match source" }

        val manifest = manifestForValidation.copy(
            name = p.name,
            defaultWidth = p.defaultWidth,
            defaultHeight = p.defaultHeight,
            warnOnResize = p.warnOnResize,
            capabilities = p.capabilities.toMutableSet(),
            sourceHash = calculatedHash
        )
        return WidgetPackage(manifest, source, compiled.bytecode, modified)
    }

    /**
     * Compatibility reader for Phase-6.1 and earlier local packages.
     * Legacy packages did carry bytecode. We validate it for corruption, then DISCARD it and
     * use bytecode freshly compiled by this client from the pinned source.
     */
    private fun decodeLegacyBinary(data: ByteArray): WidgetPackage {
        val r=R(data)
        require(r.u8()=='V'.code&&r.u8()=='W'.code&&r.u8()=='P'.code&&r.u8()=='K'.code) { "not a legacy widget package" }
        require(r.u16()==LEGACY_PACKAGE_VERSION) { "unsupported legacy widget package version" }
        val m=WidgetManifest(widgetId=r.str(),name=r.str(),author=r.str(),runtimeVersion=r.u16(),defaultWidth=r.u16(),defaultHeight=r.u16(),warnOnResize=r.bool())
        val relationOrdinal = r.u8(); require(relationOrdinal in WidgetRelation.entries.indices); m.relation=WidgetRelation.entries[relationOrdinal]
        m.sourceHash=r.str();m.upstreamWidgetId=r.str();m.upstreamSourceHash=r.str();m.localBackupSource=r.bytes().toString(Charsets.UTF_8)
        m.capabilities.clear(); var legacyRemovedCapability = false
        repeat(r.u8()) {
            val ord = r.u8()
            if (m.runtimeVersion == 1) when (ord) { 0 -> m.capabilities += WidgetCapability.Time; 1 -> legacyRemovedCapability = true; else -> error("invalid legacy widget capability") }
            else { require(ord in WidgetCapability.entries.indices); m.capabilities += WidgetCapability.entries[ord] }
        }
        val source = canonicalWidgetSource(r.bytes().toString(Charsets.UTF_8))
        val legacyBytecode = r.bytes()
        val modified = r.i64()
        require(r.done()) { "trailing legacy widget package data" }
        requireValidMetadata(m)
        require(m.runtimeVersion in 1..VeilWidgetLimits.RUNTIME_VERSION) { "unsupported widget runtime version" }
        require(!legacyRemovedCapability) { "This widget uses a capability removed from the current sandbox. Edit its source before importing it." }
        require(widgetSourceHash(source)==m.sourceHash) { "widget source hash does not match legacy package manifest" }

        val compiled=VeilWidgetLanguage.compile(source)
        require(compiled.ok && compiled.program != null) { "legacy widget source no longer compiles" }
        // Legacy VWB1/VWB2 bytecode cannot be byte-for-byte compared with current VWB5.
        // It is never executed; source hash pinning plus a fresh local VWB5 compile/verifier
        // is the trust boundary.
        if (m.runtimeVersion == VeilWidgetLimits.RUNTIME_VERSION) {
            require(compiled.bytecode.contentEquals(legacyBytecode)) { "legacy widget bytecode does not match its source" }
        }
        val verified = VeilWidgetVerifier.verifyBytecode(compiled.bytecode)
        require(verified.ok && verified.program != null) { "legacy widget failed local verifier" }
        val p = verified.program
        m.name=p.name;m.runtimeVersion=VeilWidgetLimits.RUNTIME_VERSION;m.defaultWidth=p.defaultWidth;m.defaultHeight=p.defaultHeight;m.warnOnResize=p.warnOnResize;m.capabilities=p.capabilities.toMutableSet();m.sourceHash=widgetSourceHash(source)
        return WidgetPackage(m, source, compiled.bytecode, modified)
    }

    private fun requireValidMetadata(m: WidgetManifest) {
        require(widgetIdPattern.matches(m.widgetId)) { "invalid widget id" }
        require(m.author.toByteArray(Charsets.UTF_8).size <= 512) { "widget author metadata too large" }
        if (m.upstreamWidgetId.isNotBlank()) require(widgetIdPattern.matches(m.upstreamWidgetId)) { "invalid upstream widget id" }
        if (m.upstreamSourceHash.isNotBlank()) require(Regex("^[0-9a-f]{64}$").matches(m.upstreamSourceHash)) { "invalid upstream source hash" }
        if (m.sourceHash.isNotBlank()) require(Regex("^[0-9a-f]{64}$").matches(m.sourceHash)) { "invalid source hash" }
        require(canonicalWidgetSource(m.localBackupSource).toByteArray(Charsets.UTF_8).size <= VeilWidgetLimits.MAX_SOURCE_BYTES) { "local widget backup too large" }
    }

    fun save(pkg: WidgetPackage, file: File) { file.parentFile?.mkdirs(); file.writeText(encodeText(pkg), Charsets.UTF_8) }
    fun savePublished(pkg: WidgetPackage, file: File) { file.parentFile?.mkdirs(); file.writeText(encodePublishedText(pkg), Charsets.UTF_8) }
    fun load(file: File) = decodeText(file.readText(Charsets.UTF_8))
}
