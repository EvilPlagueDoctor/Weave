package app.weave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Shared authored-profile text styling. Both the editor's TextMeasurer/drawText path
 * and the published viewer's selectable Compose Text use this precise style.
 *
 * Do not substitute Material typography here: profile authors own their font sizes,
 * colours and alignment. App controls keep their normal Material accessibility styles.
 */
internal fun authoredPageTextStyle(
    element: Element,
    color: Int = element.textArgb,
    alignment: TextAlignMode = element.textAlign,
): TextStyle = TextStyle(
    color = Color(color),
    fontSize = element.fontSize.sp,
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
