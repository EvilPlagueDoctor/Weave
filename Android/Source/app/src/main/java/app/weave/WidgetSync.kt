package app.weave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MAX_REMOTE_WIDGET_PACKAGE_BYTES = 256 * 1024
private const val WIDGET_CONTENT_TYPE = "application/x-weave-widget-source;version=1"

fun ProfileDocument.widgetElements(): List<Element> = buildList {
    pages.forEach { page ->
        fun walk(e: Element) {
            if (e.type == ElementType.Widget) add(e)
            e.children.forEach(::walk)
        }
        walk(page.root)
    }
}

private fun widgetLocalId(recordKey: String): String? =
    recordKey.removePrefix("local-widget:")
        .takeIf { recordKey.startsWith("local-widget:") && it.isNotBlank() }

/**
 * Publishes the source package for every local widget before the profile itself is published.
 *
 * The page document receives only the public blob record key plus the already-pinned source hash.
 * The blob contains SOURCE, never executable bytecode. If the same source appears more than once
 * on the page it is uploaded once and all placements share that record key.
 */
suspend fun publishPendingWidgets(
    doc: ProfileDocument,
    repo: WidgetRepository,
    controller: SocialNetworkController,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Result<Int> = withContext(Dispatchers.Default) {
    val elements = doc.widgetElements()
    if (elements.isEmpty()) return@withContext Result.success(0)

    var done = 0
    var uploaded = 0
    onProgress(0, elements.size)
    val uploadedBySourceHash = mutableMapOf<String, String>()

    for (element in elements) {
        val localId = widgetLocalId(element.widgetRecordKey)
        val pkg = when {
            localId != null -> repo.find(localId)
            element.widgetSourceHash.isNotBlank() -> repo.findBySourceHash(element.widgetSourceHash)
            else -> null
        }
        if (pkg == null) {
            // A previously published remote widget may remain on a copied page without a private
            // local library copy. Keep its existing pinned metadata rather than inventing data.
            if (localId != null || element.widgetRecordKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("A widget on this profile is missing from this account's private widget library."))
            }
            onProgress(++done, elements.size)
            continue
        }

        val canonical = canonicalWidgetSource(pkg.source)
        val compiled = VeilWidgetLanguage.compile(canonical)
        if (!compiled.ok || compiled.program == null) {
            return@withContext Result.failure(IllegalStateException("A widget on this profile no longer compiles under the current sandbox rules."))
        }
        val program = compiled.program!!
        val sourceHash = widgetSourceHash(canonical)
        if (element.widgetSourceHash.isNotBlank() && !element.widgetSourceHash.equals(sourceHash, ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException("A widget source changed after it was placed. Re-select that widget before publishing."))
        }
        element.widgetSourceHash = sourceHash
        element.widgetOnline = program.onlineMode == WidgetOnlineMode.Public
        // The publisher-owned Widget Data DHT is distinct from the participant Session DHTs.
        // It is available even to offline widgets as a small long-lived read-only databank.
        element.widgetDataDht = controller.ensureWidgetDataDht(element.id, sourceHash, program)
            ?: return@withContext Result.failure(IllegalStateException("Couldn't prepare this widget's Data DHT. Check the daemon connection and try again."))

        val needsUpload = localId != null || element.widgetRecordKey.isBlank()
        if (needsUpload) {
            val alreadyUploaded = uploadedBySourceHash[sourceHash]
            if (alreadyUploaded != null) {
                element.widgetRecordKey = alreadyUploaded
            } else {
                val encoded = runCatching { repo.exportPublishedSource(pkg) }
                    .getOrElse { return@withContext Result.failure(it) }
                    .toByteArray(Charsets.UTF_8)
                if (encoded.size > MAX_REMOTE_WIDGET_PACKAGE_BYTES) {
                    return@withContext Result.failure(IllegalStateException("Widget package is too large to publish."))
                }
                val blob = controller.uploadWidgetPackage(WIDGET_CONTENT_TYPE, encoded)
                    ?: return@withContext Result.failure(IllegalStateException("Couldn't upload widget source. Check the daemon connection and try again."))
                val recordKey = blob.optString("root_record_key")
                if (recordKey.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("The daemon accepted a widget package but returned no record key."))
                }
                element.widgetRecordKey = recordKey
                uploadedBySourceHash[sourceHash] = recordKey
                uploaded++
            }
        }
        onProgress(++done, elements.size)
    }
    Result.success(uploaded)
}

/**
 * Fetch/compile helper used only after the viewer explicitly activates a widget.
 * Merely opening or scrolling a page never calls this class.
 */
class RemoteWidgetLoader(private val controller: SocialNetworkController) {
    private val compiledCache = LinkedHashMap<String, WidgetProgram>(24, .75f, true)

    @Synchronized
    private fun cached(key: String): WidgetProgram? = compiledCache[key]

    @Synchronized
    private fun remember(key: String, program: WidgetProgram) {
        compiledCache[key] = program
        while (compiledCache.size > 24) {
            compiledCache.remove(compiledCache.keys.firstOrNull() ?: break)
        }
    }

    suspend fun load(element: Element): Result<WidgetProgram> = withContext(Dispatchers.IO) {
        if (element.type != ElementType.Widget) return@withContext Result.failure(IllegalArgumentException("Not a widget element"))
        val recordKey = element.widgetRecordKey.trim()
        if (recordKey.isBlank() || recordKey.startsWith("local-widget:")) {
            return@withContext Result.failure(IllegalStateException("This widget has not been published yet."))
        }
        val expectedSourceHash = element.widgetSourceHash.trim().lowercase()
        val cacheKey = "$recordKey|$expectedSourceHash"
        cached(cacheKey)?.let { return@withContext Result.success(it) }

        val bytes = controller.downloadWidgetPackage(recordKey, MAX_REMOTE_WIDGET_PACKAGE_BYTES)
            ?: return@withContext Result.failure(IllegalStateException("Couldn't fetch this widget package."))
        val text = bytes.toString(Charsets.UTF_8)
        if (!WidgetPackageCodec.isSourceOnlyEnvelope(text)) {
            return@withContext Result.failure(IllegalStateException("Remote widgets must use the source-only package format."))
        }
        val pkg = runCatching { WidgetPackageCodec.decodeText(text) }
            .getOrElse { return@withContext Result.failure(IllegalStateException("Widget package was rejected: ${it.message}", it)) }

        if (expectedSourceHash.isNotBlank() && pkg.manifest.sourceHash.lowercase() != expectedSourceHash) {
            return@withContext Result.failure(IllegalStateException("Widget source hash does not match the page."))
        }

        // decodeText already compiles source locally and verifies that bytecode. Verify the
        // local bytecode once more here because this is the execution boundary.
        val verified = VeilWidgetVerifier.verifyBytecode(pkg.bytecode)
        val program = verified.program
            ?: return@withContext Result.failure(IllegalStateException("Locally compiled widget did not pass the runtime verifier."))
        remember(cacheKey, program)
        Result.success(program)
    }
}
