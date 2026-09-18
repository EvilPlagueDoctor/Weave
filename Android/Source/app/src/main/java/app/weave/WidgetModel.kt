package app.weave

import java.security.MessageDigest
import java.util.UUID

/**
 * Hard limits for the untrusted widget language/runtime.
 *
 * Networking is deliberately tiny: widgets may declare at most 32 named input channels and a
 * single network event may submit at most two of those channels. A paired session may have at
 * most five unacknowledged input actions in flight, which lets turn-based widgets collect a short
 * multi-step move (for example FROM square then TO square) without a round trip between taps.
 * Text is a separate, inert data
 * path; it is never compiled or interpreted and is capped independently.
 */
object VeilWidgetLimits {
    const val RUNTIME_VERSION = 5
    const val MAX_SOURCE_BYTES = 128 * 1024
    const val MAX_NODES = 96
    const val MAX_EVENTS = 128
    const val MAX_ACTIONS_PER_EVENT = 256
    const val MAX_STRING_BYTES = 4096
    const val MIN_TIMER_MS = 250L
    const val MAX_TIMER_MS = 24L * 60L * 60L * 1000L
    const val MIN_DEFAULT_SIZE = 48
    const val MAX_DEFAULT_WIDTH = 1200
    const val MAX_DEFAULT_HEIGHT = 1200
    const val MAX_INSTRUCTIONS_PER_EVENT = 100_000
    const val MAX_TIMER_EVENTS = 8
    const val MAX_STATES = 96
    const val MAX_ARRAY_ITEMS = 128
    const val MAX_FUNCTIONS = 32
    const val MAX_FUNCTION_PARAMS = 8
    const val MAX_CALL_DEPTH = 8
    const val MAX_STATE_ID_BYTES = 32
    const val MAX_STATE_TEXT_BYTES = 1024
    const val MAX_IF_DEPTH = 8
    const val MAX_EXPRESSION_BYTES = 512
    const val MAX_EXPRESSION_TOKENS = 128
    const val MAX_BYTECODE_BYTES = 256 * 1024
    const val MIN_FONT_SIZE = 6f
    const val MAX_FONT_SIZE = 96f

    const val MAX_NETWORK_INPUTS = 32
    const val MAX_NETWORK_INPUTS_PER_EVENT = 2
    const val MAX_NETWORK_ACTIONS_IN_FLIGHT = 5
    const val MAX_NETWORK_ACTION_STEPS = 5
    const val MAX_DICE_SIDES = 1000
    const val MAX_NETWORK_TEXT_BYTES = 1024
    const val MAX_NETWORK_TEXT_MESSAGES = 5
    const val MAX_NETWORK_INPUT_ID_BYTES = 32

    const val WIDGET_SESSION_DHT_SUBKEYS = 250
    const val WIDGET_SESSION_EVENT_FIRST_SUBKEY = 1
    const val WIDGET_SESSION_EVENT_LAST_SUBKEY = 240
    const val WIDGET_SESSION_MESSAGE_FIRST_SUBKEY = 241
    const val WIDGET_SESSION_MESSAGE_LAST_SUBKEY = 245
    const val WIDGET_SESSION_STATE_SUBKEY = 246
    const val WIDGET_SESSION_TOMBSTONE_SUBKEY = 249
    const val WIDGET_DATA_DHT_SUBKEYS = 64

    const val PUBLIC_WIDGET_REQUEST_TTL_SECONDS = 60L * 60L
    const val SESSION_ROTATE_AFTER_MS = 24L * 60L * 60L * 1000L
    const val RETIRED_SESSION_KEEP_MS = 7L * 24L * 60L * 60L * 1000L
}

enum class WidgetRelation { Own, Link, Fork }
enum class WidgetCapability { Time, PublicNetwork }
enum class WidgetOnlineMode { Offline, Public }
enum class WidgetInputKind { Number, Button }
enum class WidgetStateKind { Number, Boolean, Text, NumberArray }
enum class WidgetNodeType { Box, Text, Button, TextInput }
enum class WidgetTextAlign { Left, Center, Right }
enum class WidgetTriggerType {
    Tap,
    Every,
    NetworkInput,
    NetworkCommitted,
    NetworkText,
    NetworkInvite,
    NetworkInviteAccepted,
    NetworkSessionReady,
    NetworkAction,
    NetworkActionCommitted,
    NetworkRandomReady,
    NetworkRoll,
}
enum class WidgetActionType {
    SetTextLiteral,
    SetTextTime,
    SetVisible,
    SetTextExpression,
    SetVisibleExpression,
    SetState,
    IfStart,
    ElseBranch,
    EndIf,
    NetworkSendInputs,
    NetworkSendTextLiteral,
    NetworkSendTextFrom,
    NetworkOpenInvitation,
    NetworkAccept,
    NetworkReject,
    NetworkAcceptInvite,
    NetworkDeclineInvite,
    SetEnabledExpression,
    SetBackgroundExpression,
    SetTextColorExpression,
    SetArrayElement,
    CallFunction,
    SetDynamicTextExpression,
    SetDynamicBackgroundExpression,
    NetworkBeginAction,
    NetworkEndAction,
    NetworkRoll,
}

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

data class WidgetInputDefinition(
    var id: String = "input",
    var kind: WidgetInputKind = WidgetInputKind.Button,
    var minValue: Int = 0,
    var maxValue: Int = 0,
)

data class WidgetStateDefinition(
    var id: String = "state",
    var kind: WidgetStateKind = WidgetStateKind.Number,
    /** Canonical source literal: integer, true/false, quoted UTF-8 text, or array fill value. */
    var initialValue: String = "0",
    var arraySize: Int = 0,
)

data class WidgetInputValue(
    var inputId: String = "",
    /** Only meaningful for Number inputs. Button inputs carry no sender-controlled value. */
    var numberValue: Int = 0,
    /** Program-side number expression. Wire/network events always leave this blank. */
    var numberExpression: String = "",
)

data class WidgetNode(
    var type: WidgetNodeType = WidgetNodeType.Text,
    var id: String = "item",
    var x: Float = .1f,
    var y: Float = .1f,
    var width: Float = .8f,
    var height: Float = .2f,
    var visible: Boolean = true,
    /** Text, button caption, or TextInput initial/placeholder text. */
    var text: String = "Text",
    var fontSize: Float = 20f,
    var textArgb: Int = 0xFF20242A.toInt(),
    var backgroundArgb: Int = 0x00FFFFFF,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var align: WidgetTextAlign = WidgetTextAlign.Center,
)

data class WidgetAction(
    var type: WidgetActionType = WidgetActionType.SetTextLiteral,
    var targetId: String = "",
    var value: String = "",
    var auxValue: String = "",
    var networkInputs: MutableList<WidgetInputValue> = mutableListOf(),
    var arguments: MutableList<String> = mutableListOf(),
)

data class WidgetFunction(
    var id: String = "helper",
    var params: MutableList<String> = mutableListOf(),
    var actions: MutableList<WidgetAction> = mutableListOf(),
)

data class WidgetEvent(
    var trigger: WidgetTriggerType = WidgetTriggerType.Tap,
    var targetId: String = "",
    var intervalMs: Long = 1000,
    /** For network.input/network.committed: exact declared input IDs this handler accepts. */
    var networkInputIds: MutableList<String> = mutableListOf(),
    /** For network.text(message): local read-only variable name for the inert text line. */
    var eventVariable: String = "",
    /** For network.roll handlers. */
    var networkRollSides: Int = 0,
    var actions: MutableList<WidgetAction> = mutableListOf()
)

data class WidgetProgram(
    var name: String = "My Widget",
    var defaultWidth: Int = 320,
    var defaultHeight: Int = 180,
    var warnOnResize: Boolean = true,
    var backgroundArgb: Int = 0xFFFFFFFF.toInt(),
    var onlineMode: WidgetOnlineMode = WidgetOnlineMode.Offline,
    /** Optional durable mailbox pointer copy to the widget owner in addition to the 1-hour public request. */
    var mailboxCopy: Boolean = false,
    var inputs: MutableList<WidgetInputDefinition> = mutableListOf(),
    var states: MutableList<WidgetStateDefinition> = mutableListOf(),
    var functions: MutableList<WidgetFunction> = mutableListOf(),
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
    ItemOutsideCanvas, ItemTextTooLong, TooManyActions, MissingTapTarget, MissingActionTarget,
    VerifierRejected,
    OnlineExpected, TooManyInputs, DuplicateInput, InvalidInput, NetworkRequiresOnline,
    TooManyNetworkInputs, NetworkTextTooLong, NetworkTextSourceMissing, NetworkTimerForbidden,
    TooManyStates, DuplicateState, InvalidState, InvalidExpression, IfDepth, NetworkHandlerInvalid,
    InvalidArray, InvalidFunction, DuplicateFunction, FunctionDepth, InvalidDynamicProperty,
    DynamicTargetInvalid, NetworkActionInvalid, NetworkRandomInvalid,
}
data class WidgetCompileIssue(val line: Int, val code: WidgetIssueCode, val detail: String = "")
data class WidgetCompileResult(
    val ok: Boolean,
    val program: WidgetProgram? = null,
    val bytecode: ByteArray = byteArrayOf(),
    val issues: List<WidgetCompileIssue> = emptyList()
)

fun canonicalWidgetSource(source: String): String = source
    .removePrefix("\uFEFF")
    .replace("\r\n", "\n")
    .replace('\r', '\n')

fun widgetSourceHash(source: String): String {
    val canonical = canonicalWidgetSource(source)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun WidgetProgram.deepCopy(): WidgetProgram = copy(
    inputs = inputs.map { it.copy() }.toMutableList(),
    states = states.map { it.copy() }.toMutableList(),
    functions = functions.map { fn -> fn.copy(params = fn.params.toMutableList(), actions = fn.actions.map { it.copy(networkInputs = it.networkInputs.map { v -> v.copy() }.toMutableList(), arguments = it.arguments.toMutableList()) }.toMutableList()) }.toMutableList(),
    nodes = nodes.map { it.copy() }.toMutableList(),
    events = events.map { event ->
        event.copy(
            networkInputIds = event.networkInputIds.toMutableList(),
            actions = event.actions.map { action ->
            action.copy(networkInputs = action.networkInputs.map { it.copy() }.toMutableList(), arguments = action.arguments.toMutableList())
        }.toMutableList())
    }.toMutableList(),
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
