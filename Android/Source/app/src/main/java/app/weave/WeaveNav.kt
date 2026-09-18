package app.weave

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Which half of Weave the shared Home/Search/Me controls currently describe. */
enum class BrowseMode(val label: String, val glyph: String) {
    People("People", "👤"),
    Groups("Groups", "👥"),
}

/**
 * Bottom-bar destinations. The old Activity slot is now a People/Groups mode switch, not
 * another navigation stack. Activity remains a destination reached through Settings when profile
 * moderation actually needs attention.
 */
enum class Tab(val label: String, val glyph: String) {
    Home("Home", "\u25C9"),
    Search("Search", "\u2315"),
    Me("Me", "\u25A3");

    fun root(): Destination = when (this) {
        Home -> Destination.Home
        Search -> Destination.Search
        Me -> Destination.Me
    }
}

sealed interface Destination {
    data object Home : Destination
    data object Search : Destination
    data object Me : Destination

    /** Existing profile-comment moderation/history, reached from Settings instead of a permanent tab. */
    data object Activity : Destination

    data class Profile(val mainDht: String) : Destination
    data class QuickEdit(val pageIndex: Int) : Destination
    data object Editor : Destination
    data object Settings : Destination

    data class Group(val groupId: String) : Destination
    data class GroupPost(val groupId: String, val conversationId: String) : Destination
    data class LinkedMedia(val type: WeaveObjectType, val recordKey: String, val sha256: String) : Destination
    data class GroupEditor(val groupId: String? = null) : Destination
    data object GroupModeration : Destination
}

private fun Destination.encode(): String = when (this) {
    Destination.Home -> "home"
    Destination.Search -> "search"
    Destination.Me -> "me"
    Destination.Activity -> "activity"
    Destination.Editor -> "editor"
    Destination.Settings -> "settings"
    Destination.GroupModeration -> "group-moderation"
    is Destination.Profile -> "profile\u0000$mainDht"
    is Destination.QuickEdit -> "quickedit\u0000$pageIndex"
    is Destination.Group -> "group\u0000$groupId"
    is Destination.GroupPost -> "grouppost\u0000$groupId\u0000$conversationId"
    is Destination.LinkedMedia -> "media\u0000${type.name}\u0000$recordKey\u0000$sha256"
    is Destination.GroupEditor -> "groupedit\u0000${groupId.orEmpty()}"
}

private fun decodeDestination(raw: String): Destination = when {
    raw == "home" -> Destination.Home
    raw == "search" -> Destination.Search
    raw == "me" -> Destination.Me
    raw == "activity" -> Destination.Activity
    raw == "editor" -> Destination.Editor
    raw == "settings" -> Destination.Settings
    raw == "group-moderation" -> Destination.GroupModeration
    raw.startsWith("profile\u0000") -> Destination.Profile(raw.substringAfter('\u0000'))
    raw.startsWith("quickedit\u0000") ->
        Destination.QuickEdit(raw.substringAfter('\u0000').toIntOrNull() ?: 0)
    raw.startsWith("grouppost\u0000") -> {
        val parts = raw.split('\u0000')
        Destination.GroupPost(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }
    raw.startsWith("media\u0000") -> {
        val parts = raw.split('\u0000')
        Destination.LinkedMedia(
            type = WeaveObjectType.entries.firstOrNull { it.name == parts.getOrElse(1) { "" } }
                ?: WeaveObjectType.Image,
            recordKey = parts.getOrElse(2) { "" },
            sha256 = parts.getOrElse(3) { "" },
        )
    }
    raw.startsWith("group\u0000") -> Destination.Group(raw.substringAfter('\u0000'))
    raw.startsWith("groupedit\u0000") ->
        Destination.GroupEditor(raw.substringAfter('\u0000').takeIf { it.isNotBlank() })
    else -> Destination.Home
}

private data class NavKey(val mode: BrowseMode, val tab: Tab)

/**
 * A separate back stack for every (mode, tab) pair. Switching People -> Groups therefore does
 * not destroy the profile or group the user was looking at, while tapping an already-selected
 * Home/Search/Me control still returns that section to its root.
 */
@Stable
class WeaveNavState(
    initialTab: Tab = Tab.Me,
    initialMode: BrowseMode = BrowseMode.People,
    initialStacks: Map<Pair<BrowseMode, Tab>, List<Destination>> = emptyMap(),
) {
    var tab by mutableStateOf(initialTab)
        private set

    var mode by mutableStateOf(initialMode)
        private set

    private val stacks: Map<NavKey, SnapshotStateList<Destination>> =
        BrowseMode.entries.flatMap { m -> Tab.entries.map { t -> NavKey(m, t) } }
            .associateWith { key ->
                mutableStateListOf<Destination>().apply {
                    val restored = initialStacks[key.mode to key.tab].orEmpty()
                    if (restored.isEmpty()) add(key.tab.root()) else addAll(restored)
                }
            }

    private fun stack(): SnapshotStateList<Destination> =
        stacks.getValue(NavKey(mode, tab))

    val current: Destination get() = stack().last()
    val depth: Int get() = stack().size

    fun push(destination: Destination) {
        val stack = stack()
        if (stack.last() != destination) stack.add(destination)
    }

    fun replaceTop(destination: Destination) {
        val stack = stack()
        if (stack.size == 1) stack.add(destination) else stack[stack.lastIndex] = destination
    }

    fun selectTab(next: Tab) {
        if (next == tab) popToRoot() else tab = next
    }

    fun selectMode(next: BrowseMode) {
        if (next == mode) return
        mode = next
    }

    fun toggleMode(): BrowseMode {
        mode = if (mode == BrowseMode.People) BrowseMode.Groups else BrowseMode.People
        return mode
    }

    fun popToRoot() {
        val stack = stack()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Returns false when there is nowhere left to go. */
    fun pop(): Boolean {
        val stack = stack()
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (tab != Tab.Home) {
            tab = Tab.Home
            return true
        }
        return false
    }

    internal fun snapshot(): List<String> = buildList {
        add("weave-nav-v2")
        add(mode.name)
        add(tab.name)
        BrowseMode.entries.forEach { m ->
            Tab.entries.forEach { t ->
                val s = stacks.getValue(NavKey(m, t))
                add(m.name)
                add(t.name)
                add(s.size.toString())
                s.forEach { add(it.encode()) }
            }
        }
    }

    companion object {
        val Saver: Saver<WeaveNavState, List<String>> = Saver(
            save = { it.snapshot() },
            restore = { raw ->
                runCatching {
                    if (raw.firstOrNull() != "weave-nav-v2") {
                        // Old snapshots had Activity as a fourth tab. Dropping a stale navigation
                        // snapshot is safer than reviving Activity as a permanent tab.
                        return@runCatching WeaveNavState()
                    }
                    var i = 1
                    val mode = BrowseMode.valueOf(raw[i++])
                    val tab = Tab.valueOf(raw[i++])
                    val restored = mutableMapOf<Pair<BrowseMode, Tab>, List<Destination>>()
                    while (i < raw.size) {
                        val m = BrowseMode.valueOf(raw[i++])
                        val t = Tab.valueOf(raw[i++])
                        val count = raw[i++].toInt()
                        restored[m to t] = (0 until count).map { decodeDestination(raw[i++]) }
                    }
                    WeaveNavState(tab, mode, restored)
                }.getOrElse { WeaveNavState() }
            }
        )
    }
}

@Composable
fun rememberWeaveNavState(
    initialTab: Tab = Tab.Me,
    initialMode: BrowseMode = BrowseMode.People,
): WeaveNavState = rememberSaveable(saver = WeaveNavState.Saver) {
    WeaveNavState(initialTab, initialMode)
}
