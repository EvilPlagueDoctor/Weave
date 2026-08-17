package com.veilysocial.profiledesigner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Page navigation rail
// ---------------------------------------------------------------------------

/**
 * Page controls for a book.
 *
 * Every page gets its own bar, not just the current one, so the rail shows how long the book
 * is and where you are in it. Bars are tappable, and when there are more than fit between the
 * arrows the strip scrolls rather than shrinking the bars into invisibility.
 */
@Composable
fun PageRail(
    pageNames: List<String>,
    index: Int,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    if (pageNames.size <= 1) return
    val strip = rememberScrollState()

    // Keep the current page in view when it changes from a swipe or an arrow rather than a tap.
    LaunchedEffect(index) {
        val approxBarWidth = 34
        strip.animateScrollTo((index * approxBarWidth - 60).coerceAtLeast(0))
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RailArrow(glyph = "\u25C0", enabled = index > 0, onClick = onPrev)
            Row(
                Modifier.weight(1f).horizontalScroll(strip),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pageNames.forEachIndexed { i, _ ->
                    val selected = i == index
                    Box(
                        Modifier
                            .padding(vertical = 14.dp)
                            .width(if (selected) 26.dp else 16.dp)
                            .height(if (selected) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
                            )
                            .clickable { onSelect(i) }
                    )
                }
            }
            RailArrow(glyph = "\u25B6", enabled = index < pageNames.lastIndex, onClick = onNext)
        }
    }
    Text(
        pageNames[index],
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/** A page arrow. Solid triangle on a filled circle, because the old text arrows were easy to miss. */
@Composable
private fun RailArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Same treatment for the viewer's back control, so the two read as a set. */
@Composable
fun CircleIconButton(glyph: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------------
// Pinch to zoom
// ---------------------------------------------------------------------------

@Stable
class PageZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > 1.01f

    fun apply(zoomChange: Float, panChange: Offset) {
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = if (scale <= 1.01f) Offset.Zero else offset + panChange
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private companion object {
        const val MAX_ZOOM = 4f
    }
}

@Composable
fun rememberPageZoomState(key: Any?): PageZoomState = remember(key) { PageZoomState() }

/**
 * Two-finger zoom that leaves one-finger gestures alone.
 *
 * [androidx.compose.foundation.gestures.detectTransformGestures] would swallow single-finger
 * pans as well, which would break both the vertical page scroll and the horizontal swipe
 * between profiles. This only claims the event once a second pointer is down.
 */
fun Modifier.pinchZoom(state: PageZoomState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) continue

            var zoom = 1f
            var pan = Offset.Zero
            val current = pressed.map { it.position }
            val previous = pressed.map { it.previousPosition }
            if (current.size >= 2 && previous.size >= 2) {
                val currentSpread = (current[0] - current[1]).getDistance()
                val previousSpread = (previous[0] - previous[1]).getDistance()
                if (previousSpread > 0f) zoom = currentSpread / previousSpread
                val currentCentre = (current[0] + current[1]) / 2f
                val previousCentre = (previous[0] + previous[1]) / 2f
                pan = currentCentre - previousCentre
            }
            if (zoom != 1f || pan != Offset.Zero) {
                state.apply(zoom, pan)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hit testing
// ---------------------------------------------------------------------------

/**
 * The topmost image element under a point, in canvas coordinates.
 *
 * Element rects are fractions of their parent, so absolute position accumulates down the
 * tree the same way the description extractor walks it.
 */
fun Page.imageElementAt(x: Float, y: Float, canvasWidth: Float, canvasHeight: Float): Element? {
    var best: Element? = null
    var bestZ = Int.MIN_VALUE

    fun walk(element: Element, left: Float, top: Float, width: Float, height: Float) {
        val l = left + element.rect.x * width
        val t = top + element.rect.y * height
        val w = element.rect.width * width
        val h = element.rect.height * height
        val inside = x >= l && x <= l + w && y >= t && y <= t + h
        if (inside && element.type == ElementType.Media && element.mediaKind == MediaKind.Image &&
            element.rect.zIndex >= bestZ
        ) {
            best = element
            bestZ = element.rect.zIndex
        }
        element.children.forEach { walk(it, l, t, w, h) }
    }

    root.children.forEach { walk(it, 0f, 0f, canvasWidth, canvasHeight) }
    return best
}

// ---------------------------------------------------------------------------
// Background presets for the simple editor
// ---------------------------------------------------------------------------

enum class BackgroundStyle { Solid, HardSplit, SmoothFade }

/** The four directions offered. Values are the gradient start and end in unit coordinates. */
enum class BackgroundDirection(val label: String, val sx: Float, val sy: Float, val ex: Float, val ey: Float) {
    TopToBottom("Up and down", 0f, 0f, 0f, 1f),
    LeftToRight("Left to right", 0f, 0f, 1f, 0f),
    DiagonalDown("Diagonal \\\\", 0f, 0f, 1f, 1f),
    DiagonalUp("Diagonal /", 0f, 1f, 1f, 0f),
}

/**
 * Builds a background from the simple editor's choices.
 *
 * A hard split is the same gradient with both stops doubled up at the midpoint, so there is
 * no interpolated band between the colours. Four stops, well inside the eight the format
 * allows.
 */
fun backgroundFor(
    style: BackgroundStyle,
    first: Int,
    second: Int,
    direction: BackgroundDirection,
): BackgroundSpec = when (style) {
    BackgroundStyle.Solid -> BackgroundSpec(kind = BackgroundKind.Solid, solidArgb = first)

    BackgroundStyle.SmoothFade -> BackgroundSpec(
        kind = BackgroundKind.LinearGradient,
        solidArgb = first,
        startX = direction.sx, startY = direction.sy, endX = direction.ex, endY = direction.ey,
        stops = mutableListOf(GradientStop(0f, first), GradientStop(1f, second)),
    )

    BackgroundStyle.HardSplit -> BackgroundSpec(
        kind = BackgroundKind.LinearGradient,
        solidArgb = first,
        startX = direction.sx, startY = direction.sy, endX = direction.ex, endY = direction.ey,
        stops = mutableListOf(
            GradientStop(0f, first),
            GradientStop(.5f, first),
            GradientStop(.5f, second),
            GradientStop(1f, second),
        ),
    )
}

/** Reads a background back into the simple editor's three choices. */
fun BackgroundSpec.toStyleChoices(): Triple<BackgroundStyle, Pair<Int, Int>, BackgroundDirection> {
    val direction = BackgroundDirection.entries.firstOrNull {
        abs(it.sx - startX) < .01f && abs(it.sy - startY) < .01f &&
            abs(it.ex - endX) < .01f && abs(it.ey - endY) < .01f
    } ?: BackgroundDirection.TopToBottom

    if (kind == BackgroundKind.Solid || stops.size < 2) {
        return Triple(BackgroundStyle.Solid, solidArgb to solidArgb, direction)
    }
    val first = stops.first().argb
    val last = stops.last().argb
    val hard = stops.size >= 4 && stops.any { abs(it.position - .5f) < .01f }
    return Triple(
        if (hard) BackgroundStyle.HardSplit else BackgroundStyle.SmoothFade,
        first to last,
        direction,
    )
}

/** Palette offered for backgrounds. Light options first, dark last. */
val BackgroundPalette: List<Int> = listOf(
    0xFFF7F7F7, 0xFFFFE9EC, 0xFFFFF3D6, 0xFFE6F4EA,
    0xFFE3F0FB, 0xFFEDE7F6, 0xFFF3E9DD, 0xFFFFFFFF,
    0xFF2B2D31, 0xFF1B3A2F, 0xFF23304A, 0xFF3A2340,
).map { it.toInt() }

@Composable
fun ColourRow(selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackgroundPalette.forEach { argb ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (argb == selected) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
