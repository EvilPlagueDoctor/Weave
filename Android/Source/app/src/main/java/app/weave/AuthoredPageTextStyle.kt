package app.weave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Compatibility scale for the compact text seen in pre-selectable published profiles.
 * The earlier sp <-> dp change did not change the visible size on default font settings.
 * Calibrated from the Hishiryo/Anne Kafer comparison screenshots (relative title widths
 * are about 35% of the current selectable rendering). Keep this shared by editor/viewer.
 */
internal const val PROFILE_LEGACY_TEXT_SCALE = 0.35f

/** Text size in author-design dp, before applying Android display density. */
internal fun legacyProfileFontDp(savedFontSize: Float): Float =
    (savedFontSize * PROFILE_LEGACY_TEXT_SCALE).coerceAtLeast(1f)

/**
 * Shared authored-profile text styling. Both the editor's TextMeasurer/drawText path
 * and the published viewer's selectable Compose Text use this precise style.
 * The optional viewerScale is a local reading preference, never an authored value.
 *
 * Do not substitute Material typography here: profile authors own their font sizes,
 * colours and alignment. App controls keep their normal Material accessibility styles.
 *
 * IMPORTANT: Before selectable text, published pages were drawn with Android TextPaint:
 *     textSize = element.fontSize * DrawScope.density
 * Those saved font sizes are density-independent design units (dp), NOT scaled sp.
 * We also apply PROFILE_LEGACY_TEXT_SCALE because the selectable renderer was
 * roughly 2.9x the intended proportions in existing profile screenshots.
 * dp -> sp at the active Density avoids making authored text depend on the
 * user-interface font scale.
 * Only authored page elements opt out; normal app labels remain accessible.
 */
/*
 * The old StaticLayout supplied its own line advance rather than inheriting
 * Material's bodyLarge lineHeight. Use an explicit, approximate Roboto advance
 * (1.17x) for both the editor and selectable viewer to avoid theme-dependent
 * spacing. Element positions and wrap widths are kept unchanged.
 */
internal fun authoredPageTextStyle(
    element: Element,
    density: Density,
    color: Int = element.textArgb,
    alignment: TextAlignMode = element.textAlign,
    viewerScale: Float = PROFILE_TEXT_DEFAULT_MULTIPLIER,
): TextStyle = TextStyle(
    color = Color(color),
    fontSize = with(density) { (legacyProfileFontDp(element.fontSize) * clampProfileTextMultiplier(viewerScale)).dp.toSp() },
    lineHeight = with(density) { (legacyProfileFontDp(element.fontSize) * clampProfileTextMultiplier(viewerScale) * 1.17f).dp.toSp() },
    fontWeight = if (element.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (element.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (element.underline) TextDecoration.Underline else TextDecoration.None,
    textAlign = when (alignment) {
        TextAlignMode.Left -> TextAlign.Start
        TextAlignMode.Center -> TextAlign.Center
        TextAlignMode.Right -> TextAlign.End
    },
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)
