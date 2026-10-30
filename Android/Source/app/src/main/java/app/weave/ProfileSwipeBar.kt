package app.weave

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shows the size of the opened Search/Home deck and provides accessible alternatives to swiping.
 * The arrows wrap around through [profileSwipeNeighbor].
 */
@Composable
internal fun ProfileSwipeBar(
    index: Int,
    count: Int,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    if (count <= 0) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) {
                Icon(Icons.Default.ChevronLeft, contentDescription = tr("Previous profile"))
            }
            Text(
                "${tr("Profile")} ${index.coerceAtLeast(1)} / $count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                Icon(Icons.Default.ChevronRight, contentDescription = tr("Next profile"))
            }
        }
    }
}
