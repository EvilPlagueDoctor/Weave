package app.weave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSwipeDeckTest {
    @Test fun searchResultsPreserveVisibleOrder() {
        val searchResults = listOf("a", "b", "c", "d")
        assertEquals(searchResults, profileSwipeKeys("c", searchResults))
    }

    @Test fun resultsAreDeduplicatedWithoutReordering() {
        assertEquals(listOf("b", "a", "c"), profileSwipeKeys("b", listOf("b", "a", "b", "c")))
    }

    @Test fun missingOpenProfileIsStillNavigable() {
        assertEquals(listOf("unknown", "a", "b"), profileSwipeKeys("unknown", listOf("a", "b")))
    }

    @Test fun endWrapsToStartAndBackAgain() {
        val keys = profileSwipeKeys("a", listOf("a", "b", "c"))
        assertEquals("a", profileSwipeNeighbor(keys, "c", 1))
        assertEquals("c", profileSwipeNeighbor(keys, "a", -1))
        assertEquals("b", profileSwipeNeighbor(keys, "a", 1))
        assertEquals("a", profileSwipeNeighbor(keys, "b", -1))
    }

    @Test fun singleAndMissingEntriesCannotSwipe() {
        assertNull(profileSwipeNeighbor(listOf("a"), "a", 1))
        assertNull(profileSwipeNeighbor(listOf("a", "b"), "unknown", 1))
        assertNull(profileSwipeNeighbor(listOf("a", "b"), "a", 0))
    }
}
