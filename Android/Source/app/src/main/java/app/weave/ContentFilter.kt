package app.weave

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max

/** Viewer-side filtering only. Nothing in this file removes, reports, or republishes content. */
enum class ContentFilterCategory(val label: String) {
    Sexual("Sexual content"),
    Gore("Gore / graphic content"),
    Aggression("Aggressive / hostile text"),
}

enum class FilterSensitivity(val label: String, val threshold: Float) {
    Off("Off", 2f),
    Low("Low", .85f),
    Medium("Medium", .65f),
    High("High", .45f),
}

enum class FilterAction(val label: String) {
    Show("Show normally"),
    Warn("Show warning"),
    Blur("Blur until tapped"),
    Hide("Hide until tapped"),
}

@Immutable
data class CategoryFilterPreference(
    val sensitivity: FilterSensitivity = FilterSensitivity.Off,
    val action: FilterAction = FilterAction.Blur,
)

@Immutable
data class ContentFilterPreferences(
    val sexual: CategoryFilterPreference = CategoryFilterPreference(),
    val gore: CategoryFilterPreference = CategoryFilterPreference(),
    val aggression: CategoryFilterPreference = CategoryFilterPreference(),
) {
    fun forCategory(category: ContentFilterCategory): CategoryFilterPreference = when (category) {
        ContentFilterCategory.Sexual -> sexual
        ContentFilterCategory.Gore -> gore
        ContentFilterCategory.Aggression -> aggression
    }
}

@Immutable
data class ContentScores(
    val sexual: Float = 0f,
    val explicitSexual: Float = 0f,
    val gore: Float = 0f,
    val aggression: Float = 0f,
    val threat: Float = 0f,
    val obscene: Float = 0f,
    val insult: Float = 0f,
) {
    fun scoreFor(category: ContentFilterCategory): Float = when (category) {
        ContentFilterCategory.Sexual -> max(sexual, explicitSexual)
        ContentFilterCategory.Gore -> gore
        ContentFilterCategory.Aggression -> max(aggression, threat)
    }.coerceIn(0f, 1f)

    fun merge(other: ContentScores) = ContentScores(
        sexual = max(sexual, other.sexual),
        explicitSexual = max(explicitSexual, other.explicitSexual),
        gore = max(gore, other.gore),
        aggression = max(aggression, other.aggression),
        threat = max(threat, other.threat),
        obscene = max(obscene, other.obscene),
        insult = max(insult, other.insult),
    )
}

@Immutable
data class FilterDecision(
    val action: FilterAction = FilterAction.Show,
    val category: ContentFilterCategory? = null,
    val score: Float = 0f,
) {
    val filtered: Boolean get() = category != null && action != FilterAction.Show
}

@Stable
class ContentFilter(private val context: Context) : PrivateVault.Participant {
    private val vault = PrivateVault.get(context.applicationContext)
    var preferences by mutableStateOf(ContentFilterPreferences())
        private set

    private val textCache = BoundedCache<String, ContentScores>(384)
    private val imageCache = BoundedCache<String, ContentScores>(192)
    private val revealed = mutableStateMapOf<String, Unit>()

    private val imageSafetyClassifier by lazy { OnnxImageSafetyClassifier(context) }
    private val toxicTextClassifier by lazy { OnnxToxicTextClassifier(context) }

    init { vault.register(this) }

    override fun onVaultAttached() {
        preferences = loadPreferences()
        textCache.clear()
        imageCache.clear()
        revealed.clear()
    }

    override fun onVaultDetached() {
        preferences = ContentFilterPreferences()
        textCache.clear()
        imageCache.clear()
        revealed.clear()
    }

    val imageSafetyModelReady: Boolean get() = assetExists(IMAGE_SAFETY_MODEL_ASSET)
    val sexualImageModelReady: Boolean get() = imageSafetyModelReady
    val goreImageModelReady: Boolean get() = imageSafetyModelReady
    val aggressionTextModelReady: Boolean get() = assetExists(TOXIC_MODEL_ASSET) && assetExists(VOCAB_ASSET)

    fun update(category: ContentFilterCategory, sensitivity: FilterSensitivity? = null, action: FilterAction? = null) {
        val old = preferences.forCategory(category)
        val next = old.copy(sensitivity = sensitivity ?: old.sensitivity, action = action ?: old.action)
        preferences = when (category) {
            ContentFilterCategory.Sexual -> preferences.copy(sexual = next)
            ContentFilterCategory.Gore -> preferences.copy(gore = next)
            ContentFilterCategory.Aggression -> preferences.copy(aggression = next)
        }
        savePreferences()
    }

    fun reveal(contentId: String) { revealed[contentId] = Unit }
    fun isRevealed(contentId: String): Boolean = revealed.containsKey(contentId)

    fun decision(scores: ContentScores, contentId: String): FilterDecision {
        if (isRevealed(contentId)) return FilterDecision()
        return ContentFilterCategory.entries.mapNotNull { category ->
            val preference = preferences.forCategory(category)
            if (preference.sensitivity == FilterSensitivity.Off || preference.action == FilterAction.Show) return@mapNotNull null
            val score = scores.scoreFor(category)
            if (score < preference.sensitivity.threshold) null
            else FilterDecision(preference.action, category, score)
        }.maxWithOrNull(compareBy<FilterDecision>({ it.action.ordinal }, { it.score })) ?: FilterDecision()
    }

    suspend fun classifyText(contentId: String, text: String): ContentScores {
        val cacheKey = "$contentId:${text.hashCode()}"
        textCache[cacheKey]?.let { return it }
        if (preferences.aggression.sensitivity == FilterSensitivity.Off) return ContentScores()
        val score = toxicTextClassifier.classify(text)
        textCache[cacheKey] = score
        return score
    }

    suspend fun classifyImage(contentId: String, bitmap: Bitmap): ContentScores {
        imageCache[contentId]?.let { return it }
        if (preferences.sexual.sensitivity == FilterSensitivity.Off && preferences.gore.sensitivity == FilterSensitivity.Off) {
            return ContentScores()
        }
        // One compact local model returns SFW / NSFW / NSFL (gore) probabilities.
        val score = imageSafetyClassifier.classify(bitmap)
        imageCache[contentId] = score
        return score
    }

    private fun assetExists(path: String): Boolean = runCatching { context.assets.open(path).use { } }.isSuccess

    private fun loadPreferences(): ContentFilterPreferences = runCatching {
        val root = JSONObject(vault.getText(PREFS_VAULT_KEY) ?: "{}")
        fun load(prefix: String) = CategoryFilterPreference(
            sensitivity = enumValueOrDefault(root.optString("${prefix}_sensitivity"), FilterSensitivity.Off),
            action = enumValueOrDefault(root.optString("${prefix}_action"), FilterAction.Blur),
        )
        val gore = if (root.has("gore_sensitivity") || root.has("gore_action")) load("gore") else load("violence")
        ContentFilterPreferences(load("sexual"), gore, load("aggression"))
    }.getOrElse { ContentFilterPreferences() }

    private fun savePreferences() {
        if (!vault.attached) return
        val root = JSONObject()
            .put("sexual_sensitivity", preferences.sexual.sensitivity.name)
            .put("sexual_action", preferences.sexual.action.name)
            .put("gore_sensitivity", preferences.gore.sensitivity.name)
            .put("gore_action", preferences.gore.action.name)
            .put("aggression_sensitivity", preferences.aggression.sensitivity.name)
            .put("aggression_action", preferences.aggression.action.name)
        runCatching { vault.putText(PREFS_VAULT_KEY, root.toString()) }
    }

    private fun <T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        fallback.declaringJavaClass.enumConstants.firstOrNull { it.name == value } ?: fallback

    companion object {
        private const val PREFS_VAULT_KEY = "content-filter/preferences-v1.json"
        const val IMAGE_SAFETY_MODEL_ASSET = "content_filter/image_safety_xs.onnx"
        const val TOXIC_MODEL_ASSET = "content_filter/toxic_minilm_int8.onnx"
        const val VOCAB_ASSET = "content_filter/vocab.txt"
    }
}

private class BoundedCache<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(maxEntries, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }
    @Synchronized operator fun get(key: K): V? = map[key]
    @Synchronized operator fun set(key: K, value: V) { map[key] = value }
    @Synchronized fun clear() { map.clear() }
}

private class OnnxImageSafetyClassifier(private val context: Context) {
    private val mutex = Mutex()
    private var attempted = false
    private var session: OrtSession? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    suspend fun classify(bitmap: Bitmap): ContentScores = withContext(Dispatchers.Default) {
        val active = ensureSession() ?: return@withContext ContentScores()
        mutex.withLock { classifyLocked(active, bitmap) }
    }

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (attempted) return null
        attempted = true
        session = runCatching {
            val bytes = context.assets.open(ContentFilter.IMAGE_SAFETY_MODEL_ASSET).use { it.readBytes() }
            OrtSession.SessionOptions().use { options -> env.createSession(bytes, options) }
        }.getOrNull()
        return session
    }

    private fun classifyLocked(active: OrtSession, bitmap: Bitmap): ContentScores {
        val input = active.inputInfo.entries.firstOrNull() ?: return ContentScores()
        val shape = (input.value.info as? TensorInfo)?.shape ?: return ContentScores()
        val nchw = shape.size == 4 && shape.getOrNull(1) == 3L
        val h = when {
            nchw -> shape.getOrNull(2)?.takeIf { it > 0 }?.toInt()
            else -> shape.getOrNull(1)?.takeIf { it > 0 }?.toInt()
        } ?: 224
        val w = when {
            nchw -> shape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
            else -> shape.getOrNull(2)?.takeIf { it > 0 }?.toInt()
        } ?: 224
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        // OwenElliott/image-safety-classifier-xs bakes normalization and softmax
        // into the ONNX graph. Feed RGB float pixels in the 0..255 range.
        val data = FloatArray(w * h * 3)
        if (nchw) {
            for (i in pixels.indices) {
                val c = pixels[i]
                data[i] = Color.red(c).toFloat()
                data[w * h + i] = Color.green(c).toFloat()
                data[2 * w * h + i] = Color.blue(c).toFloat()
            }
        } else {
            for (i in pixels.indices) {
                val c = pixels[i]
                val o = i * 3
                data[o] = Color.red(c).toFloat()
                data[o + 1] = Color.green(c).toFloat()
                data[o + 2] = Color.blue(c).toFloat()
            }
        }
        val tensorShape = if (nchw) longArrayOf(1, 3, h.toLong(), w.toLong()) else longArrayOf(1, h.toLong(), w.toLong(), 3)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), tensorShape).use { tensor ->
            active.run(mapOf(input.key to tensor)).use { result ->
                val probabilities = floatsFromValue(result[0].value)
                if (probabilities.size < 3) return ContentScores()
                // OwenElliott canonical order: NSFL, NSFW, SFW.
                return ContentScores(
                    gore = probabilities[0].coerceIn(0f, 1f),
                    sexual = probabilities[1].coerceIn(0f, 1f),
                )
            }
        }
    }
}

private class OnnxToxicTextClassifier(private val context: Context) {
    private val mutex = Mutex()
    private var attempted = false
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    suspend fun classify(text: String): ContentScores = withContext(Dispatchers.Default) {
        val pair = ensureLoaded() ?: return@withContext fallbackAggression(text)
        mutex.withLock { classifyLocked(pair.first, pair.second, text) }
    }

    private fun ensureLoaded(): Pair<OrtSession, WordPieceTokenizer>? {
        val s = session
        val t = tokenizer
        if (s != null && t != null) return s to t
        if (attempted) return null
        attempted = true
        runCatching {
            val model = context.assets.open(ContentFilter.TOXIC_MODEL_ASSET).use { it.readBytes() }
            val vocab = context.assets.open(ContentFilter.VOCAB_ASSET).bufferedReader().use { it.readLines() }
            session = OrtSession.SessionOptions().use { options -> env.createSession(model, options) }
            tokenizer = WordPieceTokenizer(vocab)
        }
        return session?.let { loadedSession -> tokenizer?.let { loadedSession to it } }
    }

    private fun classifyLocked(active: OrtSession, tokenizer: WordPieceTokenizer, text: String): ContentScores {
        val encoded = tokenizer.encode(text, 256)
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            active.inputNames.forEach { name ->
                val values = when (name) {
                    "input_ids" -> encoded.ids
                    "attention_mask" -> encoded.mask
                    "token_type_ids" -> encoded.types
                    else -> return@forEach
                }
                tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))
            }
            if (!tensors.containsKey("input_ids")) return ContentScores()
            active.run(tensors).use { result ->
                val logits = floatsFromValue(result[0].value)
                if (logits.size < 6) return ContentScores()
                val p = FloatArray(6) { sigmoid(logits[it]) }
                return ContentScores(
                    aggression = max(max(max(p[0], p[1]), p[3]), max(p[4], p[5])),
                    threat = p[3],
                    obscene = p[2],
                    insult = p[4],
                )
            }
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }
}

private data class EncodedText(val ids: LongArray, val mask: LongArray, val types: LongArray)

/** Small BERT-compatible tokenizer so Weave does not need a second NLP runtime. */
private class WordPieceTokenizer(vocabLines: List<String>) {
    private val vocab = vocabLines.mapIndexed { index, token -> token.trimEnd('\r') to index }.toMap()
    private val unk = vocab["[UNK]"] ?: 100
    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102

    fun encode(text: String, maxLength: Int): EncodedText {
        val pieces = mutableListOf<Int>()
        pieces += cls
        basicTokens(text).forEach tokenLoop@ { token ->
            if (pieces.size >= maxLength - 1) return@tokenLoop
            if (token.length > 100) { pieces += unk; return@tokenLoop }
            var start = 0
            val subTokens = mutableListOf<Int>()
            var failed = false
            while (start < token.length) {
                var end = token.length
                var found: Int? = null
                var foundEnd = start
                while (start < end) {
                    val candidate = (if (start > 0) "##" else "") + token.substring(start, end)
                    vocab[candidate]?.let { found = it; foundEnd = end; return@let }
                    if (found != null) break
                    end--
                }
                if (found == null) { failed = true; break }
                subTokens += found!!
                start = foundEnd
            }
            if (failed) pieces += unk else pieces += subTokens
        }
        if (pieces.size >= maxLength) pieces.subList(maxLength - 1, pieces.size).clear()
        pieces += sep
        val ids = pieces.map { it.toLong() }.toLongArray()
        return EncodedText(ids, LongArray(ids.size) { 1L }, LongArray(ids.size))
    }

    private fun basicTokens(input: String): List<String> {
        val normalized = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { out += current.toString(); current.clear() } }
        normalized.forEach { ch ->
            when {
                ch.isWhitespace() -> flush()
                ch.isLetterOrDigit() -> current.append(ch)
                else -> { flush(); out += ch.toString() }
            }
        }
        flush()
        return out
    }
}

private fun fallbackAggression(text: String): ContentScores {
    // Deliberately conservative fallback used only when the optional ONNX model is absent.
    // It catches a few explicit threats without pretending to be a general toxicity model.
    val s = text.lowercase(Locale.ROOT)
    val threat = listOf("i'll kill you", "i will kill you", "going to kill you", "i'll hurt you", "i will hurt you")
        .any { it in s }
    return if (threat) ContentScores(aggression = .9f, threat = .95f) else ContentScores()
}

private fun floatsFromValue(value: Any?): FloatArray = when (value) {
    is FloatArray -> value
    is Array<*> -> when (val first = value.firstOrNull()) {
        is FloatArray -> first
        is Array<*> -> first.filterIsInstance<Number>().map { it.toFloat() }.toFloatArray()
        else -> value.filterIsInstance<Number>().map { it.toFloat() }.toFloatArray()
    }
    else -> floatArrayOf()
}

private fun softmax(values: FloatArray): FloatArray {
    val peak = values.maxOrNull() ?: return values
    val expValues = DoubleArray(values.size) { exp((values[it] - peak).toDouble()) }
    val total = expValues.sum().takeIf { it > 0.0 } ?: return FloatArray(values.size)
    return FloatArray(values.size) { (expValues[it] / total).toFloat() }
}

private fun sigmoid(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
