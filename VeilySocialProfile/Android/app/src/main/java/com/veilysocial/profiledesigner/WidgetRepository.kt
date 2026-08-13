package com.veilysocial.profiledesigner

import android.content.Context
import java.io.File

class WidgetRepository(context: Context) {
    val dir: File = File(context.filesDir, "widgets").apply { mkdirs() }

    fun all(): List<WidgetPackage> = dir.listFiles { f -> f.isFile && f.name.endsWith(".widget.txt") }
        ?.mapNotNull { runCatching { WidgetPackageCodec.load(it) }.getOrNull() }
        ?.sortedBy { it.manifest.name.lowercase() } ?: emptyList()

    fun find(id: String): WidgetPackage? {
        val exact = File(dir, "$id.widget.txt")
        if (exact.exists()) return runCatching { WidgetPackageCodec.load(exact) }.getOrNull()
        return all().firstOrNull { it.manifest.widgetId == id }
    }

    fun save(pkg: WidgetPackage): File {
        pkg.modifiedEpochMs = System.currentTimeMillis()
        val file = File(dir, "${pkg.manifest.widgetId}.widget.txt")
        WidgetPackageCodec.save(pkg, file)
        return file
    }

    fun compileAndSave(source: String, existing: WidgetPackage? = null): Pair<WidgetCompileResult, WidgetPackage?> {
        val result = VeilWidgetLanguage.compile(source)
        if (!result.ok || result.program == null) return result to null
        val m = existing?.manifest ?: WidgetManifest()
        m.name = result.program.name
        m.runtimeVersion = VeilWidgetLimits.RUNTIME_VERSION
        m.defaultWidth = result.program.defaultWidth
        m.defaultHeight = result.program.defaultHeight
        m.warnOnResize = result.program.warnOnResize
        m.capabilities = result.program.capabilities.toMutableSet()
        m.sourceHash = widgetSourceHash(source)
        val pkg = WidgetPackage(m, source, result.bytecode, System.currentTimeMillis())
        save(pkg)
        return result to pkg
    }


    fun createStreamSample(): WidgetPackage {
        val source = """widget "Gaming Stream":
    default_width = 480
    default_height = 300
    warn_on_resize = true
    background = "#15191F"

    stream gaming:
        x = 5%
        y = 5%
        width = 90%
        height = 70%
        source = "host:gaming"
        label = "Gaming stream"

    text note:
        x = 10%
        y = 80%
        width = 80%
        height = 12%
        text = "Tap the stream to simulate playback"
        size = 16
        color = "#FFFFFF"
        align = center
"""
        val result = VeilWidgetLanguage.compile(source)
        check(result.ok && result.program != null)
        val p = result.program!!
        val manifest = WidgetManifest(name=p.name, defaultWidth=p.defaultWidth, defaultHeight=p.defaultHeight, warnOnResize=p.warnOnResize, capabilities=p.capabilities.toMutableSet(), sourceHash=widgetSourceHash(source))
        return WidgetPackage(manifest, source, result.bytecode).also(::save)
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

    fun delete(pkg: WidgetPackage) { File(dir, "${pkg.manifest.widgetId}.widget.txt").delete() }
}
