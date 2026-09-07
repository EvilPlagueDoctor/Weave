package app.weave

import java.security.MessageDigest
import java.util.UUID

object VeilWidgetLimits {
    const val RUNTIME_VERSION = 1
    const val MAX_SOURCE_BYTES = 64 * 1024
    const val MAX_NODES = 64
    const val MAX_EVENTS = 64
    const val MAX_ACTIONS_PER_EVENT = 32
    const val MAX_STRING_BYTES = 4096
    const val MIN_TIMER_MS = 250L
    const val MAX_TIMER_MS = 24L * 60L * 60L * 1000L
    const val MIN_DEFAULT_SIZE = 48
    const val MAX_DEFAULT_WIDTH = 1200
    const val MAX_DEFAULT_HEIGHT = 1200
    const val MAX_INSTRUCTIONS_PER_EVENT = 256
}

enum class WidgetRelation { Own, Link, Fork }
enum class WidgetCapability { Time, HostStream }
enum class WidgetNodeType { Box, Text, Button, Stream }
enum class WidgetTextAlign { Left, Center, Right }
enum class WidgetTriggerType { Tap, Every }
enum class WidgetActionType { SetTextLiteral, SetTextTime, SetVisible }

data class WidgetManifest(
    var widgetId: String = "widget_${UUID.randomUUID().toString().replace("-", "").take(16)}",
    var name: String = "My Widget",
    var author: String = "Local author",
    var runtimeVersion: Int = VeilWidgetLimits.RUNTIME_VERSION,
    var defaultWidth: Int = 320,
    var defaultHeight: Int = 180,
    var warnOnResize: Boolean = true,
    var capabilities: MutableSet<WidgetCapability> = mutableSetOf(),
    var sourceHash: String = "",
    var relation: WidgetRelation = WidgetRelation.Own,
    var upstreamWidgetId: String = "",
    var upstreamSourceHash: String = "",
    var localBackupSource: String = ""
)

data class WidgetNode(
    var type: WidgetNodeType = WidgetNodeType.Text,
    var id: String = "item",
    var x: Float = .1f,
    var y: Float = .1f,
    var width: Float = .8f,
    var height: Float = .2f,
    var visible: Boolean = true,
    var text: String = "Text",
    var fontSize: Float = 20f,
    var textArgb: Int = 0xFF20242A.toInt(),
    var backgroundArgb: Int = 0x00FFFFFF,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var align: WidgetTextAlign = WidgetTextAlign.Center,
    var streamSource: String = "host:main",
    var streamLabel: String = "Live stream"
)

data class WidgetAction(
    var type: WidgetActionType = WidgetActionType.SetTextLiteral,
    var targetId: String = "",
    var value: String = ""
)

data class WidgetEvent(
    var trigger: WidgetTriggerType = WidgetTriggerType.Tap,
    var targetId: String = "",
    var intervalMs: Long = 1000,
    var actions: MutableList<WidgetAction> = mutableListOf()
)

data class WidgetProgram(
    var name: String = "My Widget",
    var defaultWidth: Int = 320,
    var defaultHeight: Int = 180,
    var warnOnResize: Boolean = true,
    var backgroundArgb: Int = 0xFFFFFFFF.toInt(),
    var nodes: MutableList<WidgetNode> = mutableListOf(),
    var events: MutableList<WidgetEvent> = mutableListOf(),
    var capabilities: MutableSet<WidgetCapability> = mutableSetOf()
)

data class WidgetPackage(
    var manifest: WidgetManifest = WidgetManifest(),
    var source: String = "",
    var bytecode: ByteArray = byteArrayOf(),
    var modifiedEpochMs: Long = System.currentTimeMillis()
)

enum class WidgetIssueCode {
    SourceTooLarge, EmptySource, ExpectedHeader, TopLevelIndent, DuplicateId, ItemIndent,
    TimerRange, IntegerExpected, BoolExpected, ColorExpected, UnknownWidgetProperty,
    UnknownStatement, ExpectedItemAssignment, QuotedExpected, AlignExpected,
    UnknownNodeProperty, NumberOrPercent, EventIndent, UnsupportedAction,
    DefaultWidthRange, DefaultHeightRange, TooManyNodes, TooManyEvents, IdTooLong,
    ItemOutsideCanvas, ItemTextTooLong, TooManyActions, MissingTapTarget, MissingActionTarget
}
data class WidgetCompileIssue(val line: Int, val code: WidgetIssueCode, val detail: String = "")
data class WidgetCompileResult(
    val ok: Boolean,
    val program: WidgetProgram? = null,
    val bytecode: ByteArray = byteArrayOf(),
    val issues: List<WidgetCompileIssue> = emptyList()
)

fun widgetSourceHash(source: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun WidgetProgram.deepCopy(): WidgetProgram = copy(
    nodes = nodes.map { it.copy() }.toMutableList(),
    events = events.map { it.copy(actions = it.actions.map { a -> a.copy() }.toMutableList()) }.toMutableList(),
    capabilities = capabilities.toMutableSet()
)

fun makeStarterWidgetProgram(): WidgetProgram = WidgetProgram(
    name = "Hello Widget",
    defaultWidth = 320,
    defaultHeight = 180,
    warnOnResize = true,
    backgroundArgb = 0xFFFFFBFC.toInt(),
    nodes = mutableListOf(
        WidgetNode(
            type = WidgetNodeType.Text,
            id = "message",
            x = .08f, y = .12f, width = .84f, height = .28f,
            text = "Hello from VeilWidget!",
            fontSize = 24f,
            textArgb = 0xFF7D3440.toInt(),
            bold = true
        ),
        WidgetNode(
            type = WidgetNodeType.Button,
            id = "clock_button",
            x = .22f, y = .58f, width = .56f, height = .22f,
            text = "Show time",
            fontSize = 16f,
            textArgb = 0xFF672832.toInt(),
            backgroundArgb = 0xFFF8DDE1.toInt()
        )
    ),
    events = mutableListOf(
        WidgetEvent(
            trigger = WidgetTriggerType.Tap,
            targetId = "clock_button",
            actions = mutableListOf(WidgetAction(WidgetActionType.SetTextTime, "message", "HH:mm:ss"))
        )
    ),
    capabilities = mutableSetOf(WidgetCapability.Time)
)
