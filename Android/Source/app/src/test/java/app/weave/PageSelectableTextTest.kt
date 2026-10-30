package app.weave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSelectableTextTest {
    @Test fun defaultPageContainsSelectableTitleAndBody() {
        val placements = makeDefaultProfile().pages.first().selectableTextPlacements()
        assertEquals(2, placements.size)
        assertEquals("My Weave Page", placements[0].element.text)
        assertEquals(.09f, placements[0].x, .00001f)
        assertEquals(.82f, placements[0].width, .00001f)
    }

    @Test fun nestedBlockTextRetainsAbsolutePlacement() {
        val container = Element(type = ElementType.Block, rect = RectSpec(.2f, .3f, .5f, .5f))
        container.children += Element(type = ElementType.Text, text = "nested", rect = RectSpec(.4f, .2f, .6f, .4f))
        val page = Page(root = Element(type = ElementType.Block, children = mutableListOf(container)))
        val placement = page.selectableTextPlacements().single()
        assertEquals("nested", placement.element.text)
        assertEquals(.4f, placement.x, .00001f)
        assertEquals(.4f, placement.y, .00001f)
        assertEquals(.3f, placement.width, .00001f)
        assertEquals(.2f, placement.height, .00001f)
    }

    @Test fun hiddenTextAndHiddenParentsDoNotProduceSelectionTargets() {
        val invisible = Element(type = ElementType.Text, text = "hidden", rect = RectSpec(0f, 0f, 1f, 1f, visible = false))
        val invisibleParent = Element(type = ElementType.Block, rect = RectSpec(0f, 0f, 1f, 1f, visible = false))
        invisibleParent.children += Element(type = ElementType.Text, text = "child")
        val page = Page(root = Element(type = ElementType.Block, children = mutableListOf(invisible, invisibleParent)))
        assertTrue(page.selectableTextPlacements().isEmpty())
    }

    @Test fun buttonsLinksAndWidgetsAreNotSelectionTargets() {
        val page = Page(root = Element(type = ElementType.Block, children = mutableListOf(
            Element(type = ElementType.Button, label = "button"),
            Element(type = ElementType.Link, label = "link"),
            Element(type = ElementType.Widget, widgetLabel = "widget"),
        )))
        assertTrue(page.selectableTextPlacements().isEmpty())
    }

    @Test fun zeroSizedAndEmptyTextAreNotSelectionTargets() {
        val page = Page(root = Element(type = ElementType.Block, children = mutableListOf(
            Element(type = ElementType.Text, text = "", rect = RectSpec(0f, 0f, 1f, 1f)),
            Element(type = ElementType.Text, text = "zero width", rect = RectSpec(0f, 0f, 0f, 1f)),
        )))
        assertTrue(page.selectableTextPlacements().isEmpty())
    }
}
