package app.weave

/** A snapshot of the profiles displayed by the source list when the user opens a profile.
 * Keep that order stable even if gossip or a new search changes discovery results mid-browse.
 */
internal fun profileSwipeKeys(openedMainDht: String, visibleKeys: List<String>): List<String> {
    val keys = visibleKeys.filter { it.isNotBlank() }.distinct()
    return if (openedMainDht.isBlank() || openedMainDht in keys) keys
    else listOf(openedMainDht) + keys
}

/** Return the previous/next profile, wrapping around. A one-item deck cannot swipe. */
internal fun profileSwipeNeighbor(keys: List<String>, current: String, direction: Int): String? {
    if (keys.size < 2 || direction == 0) return null
    val index = keys.indexOf(current)
    if (index < 0) return null
    val next = (index + if (direction > 0) 1 else keys.size - 1) % keys.size
    return keys[next].takeIf { it != current }
}
