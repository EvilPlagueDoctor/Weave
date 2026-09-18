package app.weave

import android.content.Context

data class PreparedWidgetTemplate(val pkg: WidgetPackage, val program: WidgetProgram)

class WidgetRepository(context: Context) : PrivateVault.Participant {
    private val appContext = context.applicationContext
    private val vault = PrivateVault.get(appContext)
    private val legacyDir = java.io.File(appContext.filesDir, "widgets")
    private val memoryCache = linkedMapOf<String, WidgetPackage>()
    private var cacheProfileId: String? = null

    init { vault.register(this) }

    override fun onVaultAttached() {
        val profileId = vault.currentProfileId()
        synchronized(memoryCache) {
            if (cacheProfileId != null && cacheProfileId != profileId) memoryCache.clear()
            cacheProfileId = profileId
        }
        // Existing plaintext widget packages are migrated one by one, and each file is removed
        // only after the encrypted value has been accepted by the daemon.
        legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".widget.txt") }?.forEach { file ->
            runCatching {
                val pkg = WidgetPackageCodec.load(file)
                val key = "widgets/${pkg.manifest.widgetId}.widget.txt"
                if (vault.getValue(key) == null) vault.putText(key, WidgetPackageCodec.encodeVerifiedLocalText(pkg))
                check(file.delete() || !file.exists()) { "could not remove legacy plaintext widget ${file.name}" }
            }
        }
        runCatching { if (legacyDir.isDirectory && legacyDir.list().isNullOrEmpty()) legacyDir.delete() }
    }

    /** Returns only a package already prepared in this process; never touches Binder or recompiles source. */
    fun peekCached(id: String): WidgetPackage? = synchronized(memoryCache) { memoryCache[id] }

    /** Cache-only source-hash lookup for Compose/render paths that must never block the main thread. */
    fun peekCachedBySourceHash(sourceHash: String): WidgetPackage? = synchronized(memoryCache) {
        memoryCache.values.firstOrNull { it.manifest.sourceHash.equals(sourceHash, ignoreCase = true) }
    }

    fun all(): List<WidgetPackage> {
        if (!vault.attached) return emptyList()
        val keys = vault.listValueKeys()
            .filter { it.startsWith("widgets/") && it.endsWith(".widget.txt") }
        return keys.mapNotNull { key ->
            val id = key.removePrefix("widgets/").removeSuffix(".widget.txt")
            synchronized(memoryCache) { memoryCache[id] } ?: runCatching {
                vault.getText(key)?.let(WidgetPackageCodec::decodeText)?.also { decoded ->
                    synchronized(memoryCache) { memoryCache[decoded.manifest.widgetId] = decoded }
                }
            }.getOrNull()
        }.sortedBy { it.manifest.name.lowercase() }
    }

    fun find(id: String): WidgetPackage? {
        if (!vault.attached) return null
        synchronized(memoryCache) { memoryCache[id] }?.let { return it }
        val exact = vault.getText("widgets/$id.widget.txt")
        if (exact != null) return runCatching { WidgetPackageCodec.decodeText(exact) }.getOrNull()?.also { decoded ->
            synchronized(memoryCache) { memoryCache[decoded.manifest.widgetId] = decoded }
        }
        return all().firstOrNull { it.manifest.widgetId == id }
    }

    fun findBySourceHash(sourceHash: String): WidgetPackage? {
        if (sourceHash.isBlank() || !vault.attached) return null
        synchronized(memoryCache) {
            memoryCache.values.firstOrNull { it.manifest.sourceHash.equals(sourceHash, ignoreCase = true) }
        }?.let { return it }
        return all().firstOrNull { it.manifest.sourceHash.equals(sourceHash, ignoreCase = true) }
    }

    fun save(pkg: WidgetPackage) {
        pkg.modifiedEpochMs = System.currentTimeMillis()
        vault.putText("widgets/${pkg.manifest.widgetId}.widget.txt", WidgetPackageCodec.encodeVerifiedLocalText(pkg))
        synchronized(memoryCache) { memoryCache[pkg.manifest.widgetId] = pkg }
    }

    /** Source-only transport for future DHT/gossip publication. No executable bytecode leaves this client. */
    fun exportPublishedSource(pkg: WidgetPackage): String = WidgetPackageCodec.encodePublishedText(pkg)

    /** Import a source-only package, compile it locally, verify it, then store the local source copy. */
    fun importPublishedSource(text: String): WidgetPackage {
        val pkg = WidgetPackageCodec.decodeText(text)
        save(pkg)
        return pkg
    }

    /** Compile + verify a bundled template without touching the daemon-owned vault. */
    fun prepareFromTemplate(template: WidgetTemplate): PreparedWidgetTemplate {
        val canonicalSource = canonicalWidgetSource(template.source)
        val result = VeilWidgetLanguage.compile(canonicalSource)
        check(result.ok && result.program != null) {
            "Bundled widget template ${template.id} does not compile: ${result.issues.joinToString { it.code.name }}"
        }
        val program = result.program!!
        val manifest = WidgetManifest(
            name = program.name,
            author = "Weave built-in template",
            runtimeVersion = VeilWidgetLimits.RUNTIME_VERSION,
            defaultWidth = program.defaultWidth,
            defaultHeight = program.defaultHeight,
            warnOnResize = program.warnOnResize,
            capabilities = program.capabilities.toMutableSet(),
            sourceHash = widgetSourceHash(canonicalSource),
            relation = WidgetRelation.Own,
        )
        return PreparedWidgetTemplate(WidgetPackage(manifest, canonicalSource, result.bytecode), program)
    }

    fun buildFromTemplate(template: WidgetTemplate): WidgetPackage = prepareFromTemplate(template).pkg

    /** Store a just-compiled built-in without recompiling/re-verifying its 100+ KiB source. */
    fun savePrepared(prepared: PreparedWidgetTemplate) {
        val pkg = prepared.pkg
        pkg.modifiedEpochMs = System.currentTimeMillis()
        vault.putText(
            "widgets/${pkg.manifest.widgetId}.widget.txt",
            WidgetPackageCodec.encodePreparedLocalText(pkg, prepared.program)
        )
        synchronized(memoryCache) { memoryCache[pkg.manifest.widgetId] = pkg }
    }

    /** Synchronous compatibility helper. UI callers should prepare/save on background dispatchers. */
    fun createFromTemplate(template: WidgetTemplate): WidgetPackage =
        prepareFromTemplate(template).also(::savePrepared).pkg

    fun compileAndSave(source: String, existing: WidgetPackage? = null): Pair<WidgetCompileResult, WidgetPackage?> {
        val canonicalSource = canonicalWidgetSource(source)
        val result = VeilWidgetLanguage.compile(canonicalSource)
        if (!result.ok || result.program == null) return result to null
        val m = existing?.manifest ?: WidgetManifest()
        m.name = result.program.name
        m.runtimeVersion = VeilWidgetLimits.RUNTIME_VERSION
        m.defaultWidth = result.program.defaultWidth
        m.defaultHeight = result.program.defaultHeight
        m.warnOnResize = result.program.warnOnResize
        m.capabilities = result.program.capabilities.toMutableSet()
        m.sourceHash = widgetSourceHash(canonicalSource)
        val pkg = WidgetPackage(m, canonicalSource, result.bytecode, System.currentTimeMillis())
        save(pkg)
        return result to pkg
    }


    fun createStarter(): WidgetPackage {
        val program = makeStarterWidgetProgram()
        val source = VeilWidgetLanguage.sourceFor(program)
        val result = VeilWidgetLanguage.compile(source)
        check(result.ok)
        val manifest = WidgetManifest(
            name = program.name,
            defaultWidth = program.defaultWidth,
            defaultHeight = program.defaultHeight,
            warnOnResize = program.warnOnResize,
            capabilities = program.capabilities.toMutableSet(),
            sourceHash = widgetSourceHash(source)
        )
        return WidgetPackage(manifest, source, result.bytecode).also(::save)
    }

    fun linkFrom(origin: WidgetPackage): WidgetPackage {
        val manifest = WidgetManifest(
            name = "${origin.manifest.name} Link",
            author = "Local linker",
            defaultWidth = origin.manifest.defaultWidth,
            defaultHeight = origin.manifest.defaultHeight,
            warnOnResize = origin.manifest.warnOnResize,
            capabilities = origin.manifest.capabilities.toMutableSet(),
            sourceHash = origin.manifest.sourceHash,
            relation = WidgetRelation.Link,
            upstreamWidgetId = origin.manifest.widgetId,
            upstreamSourceHash = origin.manifest.sourceHash,
            localBackupSource = origin.source
        )
        return WidgetPackage(manifest, origin.source, origin.bytecode.copyOf()).also(::save)
    }

    fun forkFrom(origin: WidgetPackage): WidgetPackage {
        val manifest = WidgetManifest(
            name = "${origin.manifest.name} Fork",
            author = "Local author",
            defaultWidth = origin.manifest.defaultWidth,
            defaultHeight = origin.manifest.defaultHeight,
            warnOnResize = origin.manifest.warnOnResize,
            capabilities = origin.manifest.capabilities.toMutableSet(),
            sourceHash = origin.manifest.sourceHash,
            relation = WidgetRelation.Fork,
            upstreamWidgetId = origin.manifest.widgetId,
            upstreamSourceHash = origin.manifest.sourceHash,
            localBackupSource = origin.source
        )
        return WidgetPackage(manifest, origin.source, origin.bytecode.copyOf()).also(::save)
    }

    fun upstreamChanged(pkg: WidgetPackage): Boolean {
        if (pkg.manifest.upstreamWidgetId.isBlank()) return false
        val origin = find(pkg.manifest.upstreamWidgetId) ?: return false
        return origin.manifest.sourceHash != pkg.manifest.upstreamSourceHash
    }

    fun acceptUpstream(pkg: WidgetPackage): WidgetPackage? {
        val origin = find(pkg.manifest.upstreamWidgetId) ?: return null
        pkg.manifest.localBackupSource = pkg.source
        pkg.source = origin.source
        pkg.bytecode = origin.bytecode.copyOf()
        pkg.manifest.sourceHash = origin.manifest.sourceHash
        pkg.manifest.upstreamSourceHash = origin.manifest.sourceHash
        pkg.manifest.defaultWidth = origin.manifest.defaultWidth
        pkg.manifest.defaultHeight = origin.manifest.defaultHeight
        pkg.manifest.warnOnResize = origin.manifest.warnOnResize
        pkg.manifest.capabilities = origin.manifest.capabilities.toMutableSet()
        save(pkg)
        return pkg
    }

    fun detachUsingBackup(pkg: WidgetPackage): WidgetPackage? {
        val source = pkg.manifest.localBackupSource.ifBlank { pkg.source }
        val result = VeilWidgetLanguage.compile(source)
        if (!result.ok || result.program == null) return null
        pkg.source = source
        pkg.bytecode = result.bytecode
        pkg.manifest.sourceHash = widgetSourceHash(source)
        pkg.manifest.relation = WidgetRelation.Own
        pkg.manifest.upstreamWidgetId = ""
        pkg.manifest.upstreamSourceHash = ""
        pkg.manifest.localBackupSource = ""
        pkg.manifest.name = result.program.name
        pkg.manifest.defaultWidth = result.program.defaultWidth
        pkg.manifest.defaultHeight = result.program.defaultHeight
        pkg.manifest.warnOnResize = result.program.warnOnResize
        pkg.manifest.capabilities = result.program.capabilities.toMutableSet()
        save(pkg)
        return pkg
    }

    fun delete(pkg: WidgetPackage) {
        if (vault.attached) vault.deleteValue("widgets/${pkg.manifest.widgetId}.widget.txt")
        synchronized(memoryCache) { memoryCache.remove(pkg.manifest.widgetId) }
    }
}
