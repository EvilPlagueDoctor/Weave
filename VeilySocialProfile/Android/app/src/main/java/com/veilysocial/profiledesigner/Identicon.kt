package com.veilysocial.profiledesigner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * Deterministic avatar derived from the profile key.
 *
 * Names are not unique in a DHT and never will be, so a name plus a colour is trivially
 * copied. This is not: it comes from the key itself, so it is as unforgeable as the key.
 * It also means nobody has to upload an image to have a distinct avatar on first run,
 * which keeps the image decoder off the critical path at install time.
 */
data class IdenticonSpec(val cells: BooleanArray, val fill: Color, val background: Color) {
    override fun equals(other: Any?): Boolean =
        other is IdenticonSpec && cells.contentEquals(other.cells) && fill == other.fill && background == other.background

    override fun hashCode(): Int = cells.contentHashCode() * 31 + fill.hashCode()
}

private const val GRID = 5

fun identiconFor(key: String): IdenticonSpec {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
    val hue = ((digest[0].toInt() and 0xff) / 255f) * 360f
    // Mid saturation/value keeps every generated colour legible against light and dark chrome.
    val fill = Color.hsv(hue, .52f, .62f)
    val background = Color.hsv(hue, .16f, .95f)

    val cells = BooleanArray(GRID * GRID)
    // Only the left three columns are sampled; the rest is mirrored, which is what makes
    // identicons read as a "face" rather than as noise.
    val half = (GRID + 1) / 2
    for (col in 0 until half) {
        for (row in 0 until GRID) {
            val bit = digest[1 + col * GRID + row].toInt() and 0xff
            val on = bit % 100 < 47
            cells[row * GRID + col] = on
            cells[row * GRID + (GRID - 1 - col)] = on
        }
    }
    return IdenticonSpec(cells, fill, background)
}

@Composable
fun Identicon(key: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val spec = remember(key) { identiconFor(key) }
    Canvas(modifier.size(size).clip(RoundedCornerShape(size / 5))) {
        drawRect(spec.background)
        val cell = this.size.width / GRID
        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                if (!spec.cells[row * GRID + col]) continue
                drawRect(
                    color = spec.fill,
                    topLeft = Offset(col * cell, row * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
