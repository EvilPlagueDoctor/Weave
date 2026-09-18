package app.weave

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration

sealed interface DetectedLink {
    val raw: String

    data class Group(
        override val raw: String,
        val groupId: String,
        val creatorRoot: String,
    ) : DetectedLink

    data class Post(
        override val raw: String,
        val groupId: String,
        val creatorRoot: String,
        val conversationId: String,
    ) : DetectedLink

    data class Media(
        override val raw: String,
        val mediaType: WeaveObjectType,
        val recordKey: String,
        val sha256: String,
    ) : DetectedLink

    data class Profile(
        override val raw: String,
        val mainDht: String,
    ) : DetectedLink

    data class Dht(
        override val raw: String,
        val recordKey: String,
    ) : DetectedLink

    data class External(override val raw: String) : DetectedLink
    data class UnknownInternal(override val raw: String) : DetectedLink
}

private val LINK_REGEX = Regex(
    """(?i)(?:weave://|https?://|ftp://|mailto:|dht://)[^\s<>{}\[\]"']+"""
)

fun detectLinks(text: String): List<IntRange> =
    LINK_REGEX.findAll(text).map { match ->
        var end = match.range.last
        while (end >= match.range.first && text[end] in ".,;:!?)]}") end--
        match.range.first..end
    }.filter { !it.isEmpty() }.toList()

fun parseDetectedLink(rawValue: String): DetectedLink {
    val raw = rawValue.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    if (raw.startsWith("http://", true) ||
        raw.startsWith("https://", true) ||
        raw.startsWith("ftp://", true) ||
        raw.startsWith("mailto:", true)
    ) {
        return DetectedLink.External(raw)
    }
    if (raw.startsWith("dht://", true)) {
        return DetectedLink.Dht(raw, raw.removePrefix("dht://"))
    }
    if (!raw.startsWith("weave://", true)) return DetectedLink.UnknownInternal(raw)

    val uri = runCatching { Uri.parse(raw) }.getOrNull()
        ?: return DetectedLink.UnknownInternal(raw)
    return when (uri.authority?.lowercase()) {
        "group" -> {
            val id = uri.getQueryParameter("id").orEmpty()
            val root = uri.getQueryParameter("root").orEmpty()
            if (id.isBlank() || root.isBlank()) DetectedLink.UnknownInternal(raw)
            else DetectedLink.Group(raw, id, root)
        }
        "post" -> {
            val group = uri.getQueryParameter("group").orEmpty()
            val root = uri.getQueryParameter("root").orEmpty()
            val conversation = uri.getQueryParameter("conversation").orEmpty()
            if (group.isBlank() || root.isBlank() || conversation.isBlank()) DetectedLink.UnknownInternal(raw)
            else DetectedLink.Post(raw, group, root, conversation)
        }
        "media" -> {
            val type = when (uri.getQueryParameter("type")?.lowercase()) {
                "image" -> WeaveObjectType.Image
                "audio" -> WeaveObjectType.Audio
                else -> WeaveObjectType.Message
            }
            val record = uri.getQueryParameter("record").orEmpty()
            val hash = uri.getQueryParameter("hash").orEmpty()
            if (type !in setOf(WeaveObjectType.Image, WeaveObjectType.Audio) ||
                record.isBlank() || hash.isBlank()
            ) DetectedLink.UnknownInternal(raw)
            else DetectedLink.Media(raw, type, record, hash)
        }
        "profile" -> {
            val main = uri.getQueryParameter("main").orEmpty()
            if (main.isBlank()) DetectedLink.UnknownInternal(raw)
            else DetectedLink.Profile(raw, main)
        }
        else -> DetectedLink.UnknownInternal(raw)
    }
}

fun encodeGroupPostLink(group: GroupRecord, conversationId: String): String? {
    val root = group.rootRecordKey.takeIf { it.isNotBlank() } ?: return null
    return Uri.Builder()
        .scheme("weave")
        .authority("post")
        .appendQueryParameter("group", group.groupId)
        .appendQueryParameter("root", root)
        .appendQueryParameter("conversation", conversationId)
        .build().toString()
}

fun encodeMediaLink(ref: WeaveObjectRef): String? {
    if (ref.type !in setOf(WeaveObjectType.Image, WeaveObjectType.Audio) ||
        ref.recordKey.isBlank() || ref.objectId.isBlank()
    ) return null
    return Uri.Builder()
        .scheme("weave")
        .authority("media")
        .appendQueryParameter("type", ref.type.name.lowercase())
        .appendQueryParameter("record", ref.recordKey)
        .appendQueryParameter("hash", ref.objectId)
        .build().toString()
}

@Composable
fun AutoLinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLink: (DetectedLink) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            detectLinks(text).forEachIndexed { index, range ->
                val raw = text.substring(range)
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    range.first,
                    range.last + 1,
                )
                addStringAnnotation(
                    tag = "weave_link",
                    annotation = raw,
                    start = range.first,
                    end = range.last + 1,
                )
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { position: Offset ->
                val result = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                val annotation = annotated.getStringAnnotations("weave_link", offset, offset)
                    .firstOrNull()
                    ?: return@detectTapGestures
                onLink(parseDetectedLink(annotation.item))
            }
        },
        onTextLayout = { layout = it },
    )
}
