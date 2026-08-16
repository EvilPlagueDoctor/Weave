package com.veilysocial.profiledesigner

/** Words taken from the top of the page for the published search description. */
const val SEARCH_DESCRIPTION_WORDS = 30

/** Hard character ceiling, so one enormous word cannot produce a runaway record field. */
private const val SEARCH_DESCRIPTION_CHARS = 300

/**
 * Builds the description other people see beside a profile in search, from the text nearest
 * the top of its home page.
 *
 * The description used to be typed separately in Settings, which made it free to write bait
 * there that the page never delivered — and discovery matches on the description, so the bait
 * was the part that worked. Deriving it from the page ties the claim to the content: to rank
 * for something you have to actually put it near the top of your profile.
 *
 * Text is ordered by absolute vertical position rather than by document order, because the
 * editor lets elements be reordered freely and "first" should mean what a reader sees first,
 * not what happens to be first in the element list.
 */
fun deriveSearchDescription(
    doc: ProfileDocument,
    maxWords: Int = SEARCH_DESCRIPTION_WORDS,
): String {
    val page = doc.pages.firstOrNull { it.id == doc.defaultPageId }
        ?: doc.pages.firstOrNull()
        ?: return ""

    val name = doc.profileName.normalizedForCompare()
    val collected = mutableListOf<Pair<Float, String>>()

    // Rects are fractions of the parent, so an element's absolute position has to be
    // accumulated down the tree rather than read off the element itself.
    fun walk(element: Element, parentY: Float, parentHeight: Float) {
        val absoluteY = parentY + element.rect.y * parentHeight
        if (element.type == ElementType.Text && element.text.isNotBlank()) {
            collected += absoluteY to element.text
        }
        val childScale = element.rect.height * parentHeight
        element.children.forEach { walk(it, absoluteY, childScale) }
    }
    page.root.children.forEach { walk(it, 0f, 1f) }

    val body = collected
        .sortedBy { it.first }
        .map { it.second }
        // The topmost text on a starter profile is the person's name, which is already
        // published as the name field. Repeating it would waste the description and would
        // skew the signature toward a term everyone already matches on.
        .filterNot { it.normalizedForCompare() == name }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (body.isEmpty()) return ""

    val words = body.split(' ').filter { it.isNotBlank() }
    val kept = words.take(maxWords).joinToString(" ").take(SEARCH_DESCRIPTION_CHARS)
    return if (words.size > maxWords || body.length > SEARCH_DESCRIPTION_CHARS) "$kept…" else kept
}

private fun String.normalizedForCompare(): String =
    trim().replace(Regex("\\s+"), " ").lowercase()
