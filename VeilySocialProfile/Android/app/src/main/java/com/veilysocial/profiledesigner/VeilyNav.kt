package com.veilysocial.profiledesigner

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.Composable

/**
 * Bottom-bar destinations. These are places you live, not actions you take.
 * Page turning belongs to the page rail; settings lives inside Me.
 */
enum class Tab(val label: String, val glyph: String) {
    Home("Home", "\u25C9"),
    Search("Search", "\u2315"),
    Me("Me", "\u25A3"),
    Activity("Activity", "\u25CE");

    fun root(): Destination = when (this) {
        Home -> Destination.Home
        Search -> Destination.Search
        Me -> Destination.Me
        Activity -> Destination.Activity
    }
}

sealed interface Destination {
    data object Home : Destination
    data object Search : Destination
    data object Me : Destination
    data object Activity : Destination

    /** Someone else's book. [mainDht] identifies the profile; the page index lives in viewer state. */
    data class Profile(val mainDht: String) : Destination

    /** Simple stacked editor. [pageIndex] is the page of your own book being edited. */
    data class QuickEdit(val pageIndex: Int) : Destination
    data object Editor : Destination
    data object Settings : Destination
}

private fun Destination.encode(): String = when (this) {
    Destination.Home -> "home"
    Destination.Search -> "search"
    Destination.Me -> "me"
    Destination.Activity -> "activity"
    Destination.Editor -> "editor"
    Destination.Settings -> "settings"
    is Destination.Profile -> "profile\u0000$mainDht"
    is Destination.QuickEdit -> "quickedit\u0000$pageIndex"
}

private fun decodeDestination(raw: String): Destination = when {
    raw == "home" -> Destination.Home
    raw == "search" -> Destination.Search
    raw == "me" -> Destination.Me
    raw == "activity" -> Destination.Activity
    raw == "editor" -> Destination.Editor
    raw == "settings" -> Destination.Settings
    raw.startsWith("profile\u0000") -> Destination.Profile(raw.substringAfter('\u0000'))
    raw.startsWith("quickedit\u0000") -> Destination.QuickEdit(raw.substringAfter('\u0000').toIntOrNull() ?: 0)
    else -> Destination.Home
}

/**
 * One back stack per tab, the way every well-behaved mobile app does it: switching tabs
 * preserves where you were, tapping the tab you're already on returns to its root.
 */
@Stable
class VeilyNavState(
    initialTab: Tab = Tab.Me,
    initialStacks: Map<Tab, List<Destination>> = emptyMap(),
) {
    var tab by mutableStateOf(initialTab)
        private set

    private val stacks: Map<Tab, SnapshotStateList<Destination>> =
        Tab.values().associateWith { t ->
            mutableStateListOf<Destination>().apply {
                val restored = initialStacks[t].orEmpty()
                if (restored.isEmpty()) add(t.root()) else addAll(restored)
            }
        }

    val current: Destination get() = stacks.getValue(tab).last()

    val depth: Int get() = stacks.getValue(tab).size

    fun push(destination: Destination) {
        val stack = stacks.getValue(tab)
        if (stack.last() != destination) stack.add(destination)
    }

    /** Swaps the top of the stack. Used when swiping profile-to-profile so back doesn't
     *  replay every profile you flicked past. */
    fun replaceTop(destination: Destination) {
        val stack = stacks.getValue(tab)
        if (stack.size == 1) stack.add(destination) else stack[stack.lastIndex] = destination
    }

    fun selectTab(next: Tab) {
        if (next == tab) popToRoot() else tab = next
    }

    fun popToRoot() {
        val stack = stacks.getValue(tab)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Returns false when there is nowhere left to go, so the caller can let the OS close the app. */
    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
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
        add(tab.name)
        Tab.values().forEach { t ->
            add(t.name)
            add(stacks.getValue(t).size.toString())
            stacks.getValue(t).forEach { add(it.encode()) }
        }
    }

    companion object {
        val Saver: Saver<VeilyNavState, List<String>> = Saver(
            save = { it.snapshot() },
            restore = { raw ->
                runCatching {
                    var i = 0
                    val tab = Tab.valueOf(raw[i++])
                    val stacks = mutableMapOf<Tab, List<Destination>>()
                    while (i < raw.size) {
                        val t = Tab.valueOf(raw[i++])
                        val count = raw[i++].toInt()
                        stacks[t] = (0 until count).map { decodeDestination(raw[i++]) }
                    }
                    VeilyNavState(tab, stacks)
                }.getOrElse { VeilyNavState() }
            }
        )
    }
}

@Composable
fun rememberVeilyNavState(initialTab: Tab = Tab.Me): VeilyNavState =
    rememberSaveable(saver = VeilyNavState.Saver) { VeilyNavState(initialTab) }
