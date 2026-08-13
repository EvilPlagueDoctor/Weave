package com.veilysocial.profiledesigner

import java.util.UUID

object VspfLimits {
    const val FORMAT_VERSION = 3
    const val MAX_PAGES = 64
    const val MAX_DEPTH = 16
    const val MAX_ELEMENTS_PER_PAGE = 2048
    const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_GRADIENT_STOPS = 8
    const val MAX_DECORATION_DIMENSION = 256
    const val MAX_DECORATION_ITEM_BYTES = 64 * 1024
    const val MAX_DECORATION_PACK_BYTES = 1024 * 1024
}

enum class BackgroundKind { Solid, LinearGradient }
enum class DecorationKind { Builtin, DecorationPack }
enum class ElementType { Block, Text, Link, Button, Stamp, Media, Widget }
enum class LayoutMode { Freeform, Column, Row }
enum class StickyMode { Normal, StickyParent, FloatViewport }
enum class LinkTargetType { Page, Profile, Post, Community, Dht, External }
enum class MediaKind { Image, Audio, Video }
enum class TextAlignMode { Left, Center, Right }

data class GradientStop(var position: Float = 0f, var argb: Int = 0xFF000000.toInt())
data class BackgroundSpec(
    var kind: BackgroundKind = BackgroundKind.Solid,
    var solidArgb: Int = 0xFFF7F7F7.toInt(),
    var startX: Float = 0f,
    var startY: Float = 0f,
    var endX: Float = 1f,
    var endY: Float = 1f,
    var stops: MutableList<GradientStop> = mutableListOf(
        GradientStop(0f, 0xFFF7F7F7.toInt()), GradientStop(1f, 0xFFE5E7EB.toInt())
    )
)

data class DecorationRef(
    var kind: DecorationKind = DecorationKind.Builtin,
    var builtinName: String = "Thin1",
    var packRecordKey: String = "",
    var itemId: Long = 0,
    var contentHash: String = "",
    var basedOnBuiltin: String = "Thin1"
)

data class RectSpec(
    var x: Float = .05f,
    var y: Float = .05f,
    var width: Float = .30f,
    var height: Float = .12f,
    var zIndex: Int = 0,
    var visible: Boolean = true
)

data class Element(
    var type: ElementType = ElementType.Block,
    var id: String = makeId("item"),
    var name: String = "Item",
    var rect: RectSpec = RectSpec(),

    var layout: LayoutMode = LayoutMode.Freeform,
    var background: BackgroundSpec = BackgroundSpec(),
    var border: DecorationRef = DecorationRef(),
    var borderThickness: Float = 1f,
    var clipChildren: Boolean = true,
    var scrollChildren: Boolean = false,
    var sticky: StickyMode = StickyMode.Normal,
    var children: MutableList<Element> = mutableListOf(),

    var text: String = "Text",
    var fontId: String = "Default1",
    var fontSize: Float = 18f,
    var textArgb: Int = 0xFF111827.toInt(),
    var textAlign: TextAlignMode = TextAlignMode.Left,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,

    var label: String = "Link",
    var targetType: LinkTargetType = LinkTargetType.Page,
    var target: String = "",
    var buttonDecoration: DecorationRef = DecorationRef(),

    var stampDecoration: DecorationRef = DecorationRef(builtinName = "Star1", basedOnBuiltin = "Star1"),
    var rotationDegrees: Float = 0f,
    var opacity: Float = 1f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,

    var mediaKind: MediaKind = MediaKind.Image,
    var mediaRecordKey: String = "",
    var mediaContentHash: String = "",
    var intrinsicWidth: Long = 640,
    var intrinsicHeight: Long = 480,
    var mediaTitle: String = "Image",
    var mediaDescription: String = "Media placeholder",

    var widgetLabel: String = "Widget",
    var widgetRecordKey: String = "",
    var widgetItemId: Long = 0,
    var widgetSourceHash: String = "",
    var widgetDefaultWidth: Long = 320,
    var widgetDefaultHeight: Long = 180,
    var widgetWarnOnResize: Boolean = true
)

data class Page(
    var id: String = makeId("page"),
    var name: String = "Page",
    var aspectRatio: Float = .60f,
    var root: Element = Element(type = ElementType.Block, name = "Page", rect = RectSpec(0f, 0f, 1f, 1f))
)

data class ProfileDocument(
    var profileId: String = makeId("profile"),
    var profileName: String = "My Profile",
    var defaultPageId: String = "home",
    var pages: MutableList<Page> = mutableListOf()
)

fun makeId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

fun Element.deepCopy(): Element = copy(
    rect = rect.copy(),
    background = background.copy(stops = background.stops.map { it.copy() }.toMutableList()),
    border = border.copy(),
    children = children.map { it.deepCopy() }.toMutableList(),
    buttonDecoration = buttonDecoration.copy(),
    stampDecoration = stampDecoration.copy()
)

fun ProfileDocument.deepCopy(): ProfileDocument = copy(
    pages = pages.map { it.copy(root = it.root.deepCopy()) }.toMutableList()
)

fun makeDefaultProfile(): ProfileDocument {
    val root = Element(
        type = ElementType.Block,
        id = makeId("root"),
        name = "Home Page",
        rect = RectSpec(0f, 0f, 1f, 1f),
        background = BackgroundSpec(
            kind = BackgroundKind.LinearGradient,
            startX = 0f, startY = 0f, endX = 1f, endY = 1f,
            stops = mutableListOf(
                GradientStop(0f, 0xFFF7F4FF.toInt()),
                GradientStop(.55f, 0xFFEAF4FF.toInt()),
                GradientStop(1f, 0xFFFDF2F8.toInt())
            )
        )
    )
    // Root-level stamps are the page-background decorations. The editor treats
    // them as a dedicated background workspace while keeping the wire format
    // simple and compatible with the existing VSPF tree model.
    root.children += Element(
        type = ElementType.Stamp, id = makeId("stamp"), name = "Star",
        rect = RectSpec(.05f, .05f, .08f, .08f, 0),
        stampDecoration = DecorationRef(builtinName = "Star1", basedOnBuiltin = "Star1"), rotationDegrees = -12f, opacity = .75f
    )
    root.children += Element(
        type = ElementType.Stamp, id = makeId("stamp"), name = "Star 2",
        rect = RectSpec(.84f, .13f, .055f, .055f, 1),
        stampDecoration = DecorationRef(builtinName = "Star1", basedOnBuiltin = "Star1"), rotationDegrees = 23f, opacity = .75f
    )

    val header = Element(
        type = ElementType.Block, id = makeId("block"), name = "Header Box",
        rect = RectSpec(.07f, .055f, .86f, .13f, 2),
        background = BackgroundSpec(solidArgb = 0x00FFFFFF),
        border = DecorationRef(builtinName = "None", basedOnBuiltin = "None")
    )
    header.children += Element(
        type = ElementType.Text, id = makeId("text"), name = "Profile Title",
        rect = RectSpec(.02f, .12f, .96f, .70f, 1), text = "My VeilySocial Page",
        fontSize = 30f, bold = true, textAlign = TextAlignMode.Center
    )
    root.children += header

    val box = Element(
        type = ElementType.Block, id = makeId("block"), name = "Welcome Box",
        rect = RectSpec(.10f, .22f, .80f, .58f, 3),
        background = BackgroundSpec(solidArgb = 0xFFFFFFFF.toInt()),
        border = DecorationRef(builtinName = "Thin1", basedOnBuiltin = "Thin1"), borderThickness = 2f
    )
    box.children += Element(
        type = ElementType.Text, id = makeId("text"), name = "Welcome Text",
        rect = RectSpec(.07f, .08f, .86f, .34f, 1),
        text = "Drag, resize and decorate this page. The profile document stays non-executable; compiled widgets run only inside their own sandboxed rectangles.",
        fontSize = 17f
    )
    box.children += Element(
        type = ElementType.Widget, id = makeId("widget"), name = "Widget Placeholder",
        rect = RectSpec(.18f, .52f, .64f, .35f, 2), widgetLabel = "Future Widget"
    )
    root.children += box

    return ProfileDocument(
        profileName = "My Profile", defaultPageId = "home",
        pages = mutableListOf(Page(id = "home", name = "Home", aspectRatio = .60f, root = root))
    )
}
