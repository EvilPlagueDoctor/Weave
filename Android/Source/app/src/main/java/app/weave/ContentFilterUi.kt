package app.weave

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalContentFilter = staticCompositionLocalOf<ContentFilter> {
    error("ContentFilter was not provided")
}

@Composable
fun ContentFilterSettingsSection(filter: ContentFilter = LocalContentFilter.current) {
    Text("Content filtering", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "These filters run on this device for you. They do not report people, remove posts, or change what anyone else sees.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )

    FilterPreferenceRow(
        category = ContentFilterCategory.Sexual,
        preference = filter.preferences.sexual,
        status = if (filter.sexualImageModelReady) "Image model ready" else "Image model not installed",
        onSensitivity = { filter.update(ContentFilterCategory.Sexual, sensitivity = it) },
        onAction = { filter.update(ContentFilterCategory.Sexual, action = it) },
    )
    FilterPreferenceRow(
        category = ContentFilterCategory.Gore,
        preference = filter.preferences.gore,
        status = if (filter.goreImageModelReady) "Image model ready" else "Image model not installed",
        onSensitivity = { filter.update(ContentFilterCategory.Gore, sensitivity = it) },
        onAction = { filter.update(ContentFilterCategory.Gore, action = it) },
    )
    FilterPreferenceRow(
        category = ContentFilterCategory.Aggression,
        preference = filter.preferences.aggression,
        status = if (filter.aggressionTextModelReady) "Comment model ready" else "Comment model not installed (explicit-threat fallback only)",
        onSensitivity = { filter.update(ContentFilterCategory.Aggression, sensitivity = it) },
        onAction = { filter.update(ContentFilterCategory.Aggression, action = it) },
    )

    Text(
        "Sensitivity changes how readily something is flagged. The action controls what Weave does after your filter flags it. All filters are off by default.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FilterPreferenceRow(
    category: ContentFilterCategory,
    preference: CategoryFilterPreference,
    status: String,
    onSensitivity: (FilterSensitivity) -> Unit,
    onAction: (FilterAction) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(category.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EnumDropDown(
                    label = "Sensitivity",
                    selected = preference.sensitivity.label,
                    options = FilterSensitivity.entries.map { it.label to { onSensitivity(it) } },
                    modifier = Modifier.weight(1f),
                )
                EnumDropDown(
                    label = "When detected",
                    selected = preference.action.label,
                    options = FilterAction.entries.map { it.label to { onAction(it) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EnumDropDown(
    label: String,
    selected: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(selected, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, action) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

@Composable
fun FilteredAutoLinkText(
    contentId: String,
    text: String,
    classificationText: String = text,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onLink: (DetectedLink) -> Unit,
) {
    val filter = LocalContentFilter.current
    val prefs = filter.preferences
    val active = prefs.aggression.sensitivity != FilterSensitivity.Off && prefs.aggression.action != FilterAction.Show
    var scores by remember(contentId, classificationText) { mutableStateOf<ContentScores?>(null) }
    LaunchedEffect(contentId, classificationText, prefs.aggression.sensitivity) {
        scores = if (prefs.aggression.sensitivity == FilterSensitivity.Off)
            ContentScores() else filter.classifyText(contentId, classificationText)
    }
    if (active && scores == null) {
        FilterCheckingPlaceholder(modifier)
        return
    }
    val decision = filter.decision(scores ?: ContentScores(), contentId)
    FilterGate(contentId = contentId, decision = decision, modifier = modifier) { gateModifier ->
        AutoLinkText(text = text, modifier = gateModifier, style = style, onLink = onLink)
    }
}

@Composable
fun FilteredPlainText(
    contentId: String,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val filter = LocalContentFilter.current
    val prefs = filter.preferences
    val active = prefs.aggression.sensitivity != FilterSensitivity.Off && prefs.aggression.action != FilterAction.Show
    var scores by remember(contentId, text) { mutableStateOf<ContentScores?>(null) }
    LaunchedEffect(contentId, text, prefs.aggression.sensitivity) {
        scores = if (prefs.aggression.sensitivity == FilterSensitivity.Off)
            ContentScores() else filter.classifyText(contentId, text)
    }
    if (active && scores == null) {
        FilterCheckingPlaceholder(modifier)
        return
    }
    val decision = filter.decision(scores ?: ContentScores(), contentId)
    FilterGate(contentId = contentId, decision = decision, modifier = modifier) { gateModifier ->
        Text(text = text, modifier = gateModifier, style = style, maxLines = maxLines, overflow = overflow)
    }
}

@Composable
fun FilteredImage(
    contentId: String,
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    FilteredImageInternal(contentId, bitmap, modifier, compact, content)
}

@Composable
fun FilteredImage(
    contentId: String,
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    FilteredImageInternal(contentId, bitmap.asAndroidBitmap(), modifier, compact, content)
}

@Composable
private fun FilteredImageInternal(
    contentId: String,
    bitmap: Bitmap,
    modifier: Modifier,
    compact: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    val filter = LocalContentFilter.current
    val prefs = filter.preferences
    val active = (prefs.sexual.sensitivity != FilterSensitivity.Off && prefs.sexual.action != FilterAction.Show) ||
        (prefs.gore.sensitivity != FilterSensitivity.Off && prefs.gore.action != FilterAction.Show)
    var scores by remember(contentId, bitmap) { mutableStateOf<ContentScores?>(null) }
    LaunchedEffect(contentId, bitmap, prefs.sexual.sensitivity, prefs.gore.sensitivity) {
        scores = filter.classifyImage(contentId, bitmap)
    }
    if (active && scores == null) {
        FilterCheckingPlaceholder(modifier, compact)
        return
    }
    val decision = filter.decision(scores ?: ContentScores(), contentId)
    FilterGate(contentId = contentId, decision = decision, modifier = modifier, compact = compact) { childModifier ->
        content(childModifier)
    }
}

@Composable
private fun FilterCheckingPlaceholder(modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(if (compact) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(if (compact) 12.dp else 14.dp), strokeWidth = 2.dp)
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                Text("Checking your content filters…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FilterGate(
    contentId: String,
    decision: FilterDecision,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    val filter = LocalContentFilter.current
    val category = decision.category?.label ?: "content"

    when (decision.action) {
        FilterAction.Show -> Box(modifier) { content(Modifier) }
        FilterAction.Warn -> if (compact) {
            Box(modifier) {
                content(Modifier)
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                ) {
                    Text("!", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        } else Column(modifier) {
            FilterNotice("Your $category filter flagged this content.", false) {
                filter.reveal(contentId)
            }
            content(Modifier)
        }
        FilterAction.Blur -> Box(modifier) {
            content(Modifier.blur(16.dp))
            Surface(
                modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .45f)).clickable {
                    filter.reveal(contentId)
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = .70f),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(if (compact) 3.dp else 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (compact) {
                        Text("Filtered", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Blurred by your $category filter", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text("Tap to show anyway", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        FilterAction.Hide -> if (compact) {
            Surface(
                modifier = modifier.clickable { filter.reveal(contentId) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Hidden", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        } else Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            FilterNotice("Hidden by your $category filter.", true) {
                filter.reveal(contentId)
            }
        }
    }
}

@Composable
private fun FilterNotice(text: String, prominent: Boolean, onReveal: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(if (prominent) 12.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReveal) { Text("Show anyway") }
    }
}
