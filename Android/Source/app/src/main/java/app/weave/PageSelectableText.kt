package app.weave

/** A visible text element positioned in the same normalized coordinates as the page canvas. */
data class PageSelectableTextPlacement(
    val element: Element,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Text in a published profile is normally rasterized by Canvas and cannot be selected.
 * Keep the canvas for decoration, but draw these nodes as real Compose text in the viewer.
 * Recursing through blocks is important for older profiles with nested text elements.
 */
fun Page.selectableTextPlacements(): List<PageSelectableTextPlacement> = buildList {
    fun walk(e: Element, x: Float, y: Float, width: Float, height: Float) {
        if (!e.rect.visible || width <= 0f || height <= 0f) return
        val childX = x + e.rect.x * width
        val childY = y + e.rect.y * height
        val childWidth = e.rect.width * width
        val childHeight = e.rect.height * height
        if (e.type == ElementType.Text && e.text.isNotEmpty() && childWidth > 0f && childHeight > 0f) {
            add(PageSelectableTextPlacement(e, childX, childY, childWidth, childHeight))
        }
        if (e.type == ElementType.Block) {
            e.children.sortedBy { it.rect.zIndex }.forEach { walk(it, childX, childY, childWidth, childHeight) }
        }
    }
    root.children.sortedBy { it.rect.zIndex }.forEach { walk(it, 0f, 0f, 1f, 1f) }
}
