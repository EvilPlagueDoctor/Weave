package app.weave

/**
 * Independent validation barrier between the widget compiler and runtime.
 *
 * Published packages never supply executable bytecode. The receiver compiles source locally and
 * this verifier independently rejects malformed bytecode, undeclared networking, bad expressions,
 * out-of-bounds state/array definitions, unsafe dynamic UI writes, recursive helper functions and
 * invalid receive/random/action handlers.
 */
data class WidgetVerificationResult(
    val ok: Boolean,
    val program: WidgetProgram? = null,
    val errors: List<String> = emptyList()
)

object VeilWidgetVerifier {
    private val idPattern = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private val shortIdPattern = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")

    fun verifyBytecode(bytecode: ByteArray): WidgetVerificationResult {
        if (bytecode.size > VeilWidgetLimits.MAX_BYTECODE_BYTES) {
            return WidgetVerificationResult(false, errors = listOf("bytecode exceeds ${VeilWidgetLimits.MAX_BYTECODE_BYTES} bytes"))
        }
        val program = try { VeilWidgetBytecode.decode(bytecode) }
        catch (t: Throwable) { return WidgetVerificationResult(false, errors = listOf("invalid bytecode: ${t.message ?: t::class.java.simpleName}")) }
        return verifyProgram(program)
    }

    fun verifyProgram(program: WidgetProgram): WidgetVerificationResult {
        val errors = mutableListOf<String>()
        fun String.byteSize() = toByteArray(Charsets.UTF_8).size
        fun checkString(label: String, value: String, max: Int = VeilWidgetLimits.MAX_STRING_BYTES) {
            if (value.byteSize() > max) errors += "$label is too large"
        }

        checkString("widget name", program.name)
        if (program.defaultWidth !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_WIDTH) errors += "default width is outside the allowed range"
        if (program.defaultHeight !in VeilWidgetLimits.MIN_DEFAULT_SIZE..VeilWidgetLimits.MAX_DEFAULT_HEIGHT) errors += "default height is outside the allowed range"
        if (program.nodes.size > VeilWidgetLimits.MAX_NODES) errors += "too many nodes"
        if (program.events.size > VeilWidgetLimits.MAX_EVENTS) errors += "too many events"
        if (program.inputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS) errors += "too many network inputs"
        if (program.states.size > VeilWidgetLimits.MAX_STATES) errors += "too many state values"
        if (program.functions.size > VeilWidgetLimits.MAX_FUNCTIONS) errors += "too many helper functions"
        if (program.mailboxCopy && program.onlineMode != WidgetOnlineMode.Public) errors += "mail_copy requires online = public"

        val nodeIds = mutableSetOf<String>()
        val nodesById = linkedMapOf<String, WidgetNode>()
        program.nodes.forEach { node ->
            if (!idPattern.matches(node.id)) errors += "invalid node id '${node.id.take(32)}'"
            if (!nodeIds.add(node.id)) errors += "duplicate node id '${node.id.take(32)}'"
            nodesById[node.id] = node
            if (!node.x.isFinite() || !node.y.isFinite() || !node.width.isFinite() || !node.height.isFinite()) errors += "node '${node.id}' has a non-finite position/size"
            if (node.x !in 0f..1f || node.y !in 0f..1f || node.width <= 0f || node.height <= 0f || node.x + node.width > 1.0001f || node.y + node.height > 1.0001f) errors += "node '${node.id}' is outside the widget canvas"
            if (!node.fontSize.isFinite() || node.fontSize !in VeilWidgetLimits.MIN_FONT_SIZE..VeilWidgetLimits.MAX_FONT_SIZE) errors += "node '${node.id}' has an invalid font size"
            checkString("node '${node.id}' text", node.text)
        }

        val inputIds = mutableSetOf<String>()
        program.inputs.forEach { input ->
            if (!shortIdPattern.matches(input.id)) errors += "invalid input id '${input.id.take(32)}'"
            if (!inputIds.add(input.id)) errors += "duplicate input id '${input.id.take(32)}'"
            if (input.id in nodeIds) errors += "input '${input.id}' conflicts with a node id"
            if (input.kind == WidgetInputKind.Number && input.minValue > input.maxValue) errors += "input '${input.id}' has an invalid range"
        }
        if (program.inputs.isNotEmpty() && program.onlineMode != WidgetOnlineMode.Public) errors += "network inputs require online = public"
        val defs = program.inputs.associateBy { it.id }

        val stateIds = mutableSetOf<String>()
        val stateKinds = linkedMapOf<String, WidgetStateKind>()
        val arrayIds = mutableSetOf<String>()
        program.states.forEach { state ->
            if (!shortIdPattern.matches(state.id)) errors += "invalid state id '${state.id.take(32)}'"
            if (!stateIds.add(state.id)) errors += "duplicate state id '${state.id.take(32)}'"
            if (state.id in nodeIds || state.id in inputIds) errors += "state '${state.id}' conflicts with another id"
            if (state.kind == WidgetStateKind.NumberArray) {
                if (state.arraySize !in 1..VeilWidgetLimits.MAX_ARRAY_ITEMS) errors += "state '${state.id}' has invalid array size"
                if (state.initialValue.toLongOrNull() == null) errors += "state '${state.id}' has invalid array fill value"
                arrayIds += state.id
            } else {
                val initial = runCatching { WidgetExpressions.literalValue(state.initialValue) }.getOrNull()
                if (initial == null || initial.kind() != state.kind) errors += "state '${state.id}' has an invalid initial value"
                if (initial is WidgetValue.Text && initial.value.byteSize() > VeilWidgetLimits.MAX_STATE_TEXT_BYTES) errors += "state '${state.id}' initial text is too large"
            }
            stateKinds[state.id] = state.kind
        }

        val functionIds = mutableSetOf<String>()
        val functionsById = linkedMapOf<String, WidgetFunction>()
        program.functions.forEach { fn ->
            if (!shortIdPattern.matches(fn.id)) errors += "invalid function id '${fn.id.take(32)}'"
            if (!functionIds.add(fn.id)) errors += "duplicate function id '${fn.id.take(32)}'"
            if (fn.id in nodeIds || fn.id in inputIds || fn.id in stateIds) errors += "function '${fn.id}' conflicts with another id"
            if (fn.params.size > VeilWidgetLimits.MAX_FUNCTION_PARAMS || fn.params.distinct().size != fn.params.size) errors += "function '${fn.id}' has an invalid parameter list"
            fn.params.forEach { p -> if (!shortIdPattern.matches(p) || p in stateIds || p in nodeIds || p in inputIds) errors += "function '${fn.id}' has invalid parameter '$p'" }
            if (fn.actions.size > VeilWidgetLimits.MAX_ACTIONS_PER_EVENT) errors += "function '${fn.id}' has too many actions"
            functionsById[fn.id] = fn
        }

        val hostSymbols = mapOf(
            "network.my_player" to WidgetStateKind.Number,
            "network.event_player" to WidgetStateKind.Number,
            "network.random_first_player" to WidgetStateKind.Number,
            "network.roll_index" to WidgetStateKind.Number,
            "network.roll_sides" to WidgetStateKind.Number,
            "network.action_count" to WidgetStateKind.Number,
        )

        fun symbolsFor(event: WidgetEvent): Pair<Map<String, WidgetStateKind>, Set<String>> {
            val symbols = linkedMapOf<String, WidgetStateKind>()
            symbols.putAll(stateKinds); symbols.putAll(hostSymbols)
            val arrays = arrayIds.toMutableSet()
            if (event.trigger == WidgetTriggerType.NetworkInput || event.trigger == WidgetTriggerType.NetworkCommitted) {
                event.networkInputIds.forEach { id -> defs[id]?.let { symbols[id] = if (it.kind == WidgetInputKind.Number) WidgetStateKind.Number else WidgetStateKind.Boolean } }
            }
            if (event.trigger == WidgetTriggerType.NetworkAction || event.trigger == WidgetTriggerType.NetworkActionCommitted) {
                event.networkInputIds.forEach { id -> val name = "network.action.$id"; symbols[name] = WidgetStateKind.NumberArray; arrays += name }
            }
            if (event.trigger == WidgetTriggerType.NetworkText && event.eventVariable.isNotBlank()) symbols[event.eventVariable] = WidgetStateKind.Text
            if (event.trigger == WidgetTriggerType.NetworkRoll && event.eventVariable.isNotBlank()) symbols[event.eventVariable] = WidgetStateKind.Number
            return symbols to arrays
        }

        fun checkExpression(label: String, expr: String, symbols: Map<String, WidgetStateKind>, arrays: Set<String>, required: WidgetStateKind? = null) {
            val checked = WidgetExpressions.check(expr, symbols, arrays)
            if (!checked.ok) errors += "$label has invalid expression: ${checked.error}"
            else if (required != null && checked.kind != required) errors += "$label expression must be ${required.name.lowercase()}"
        }

        fun verifyActions(actions: List<WidgetAction>, trigger: WidgetTriggerType?, symbols: Map<String, WidgetStateKind>, arrays: Set<String>, functionContext: Boolean, label: String) {
            var ifDepth = 0
            val elseSeen = mutableListOf<Boolean>()
            actions.forEach { action ->
                when (action.type) {
                    WidgetActionType.SetTextLiteral -> { if (action.targetId !in nodeIds) errors += "$label targets a missing node"; checkString("$label literal", action.value) }
                    WidgetActionType.SetTextTime -> { if (action.targetId !in nodeIds) errors += "$label targets a missing node"; checkString("$label time format", action.value, 128) }
                    WidgetActionType.SetVisible -> {
                        if (action.targetId !in nodeIds) errors += "$label targets a missing node"
                        if (!action.value.equals("true", true) && !action.value.equals("false", true)) errors += "$label has invalid visibility value"
                    }
                    WidgetActionType.SetTextExpression -> { if (action.targetId !in nodeIds) errors += "$label targets a missing node"; checkExpression("$label text", action.value, symbols, arrays) }
                    WidgetActionType.SetVisibleExpression -> { if (action.targetId !in nodeIds) errors += "$label targets a missing node"; checkExpression("$label visibility", action.value, symbols, arrays, WidgetStateKind.Boolean) }
                    WidgetActionType.SetEnabledExpression -> {
                        val n = nodesById[action.targetId]
                        if (n == null || n.type !in setOf(WidgetNodeType.Button, WidgetNodeType.TextInput)) errors += "$label writes unsupported enabled property"
                        checkExpression("$label enabled", action.value, symbols, arrays, WidgetStateKind.Boolean)
                    }
                    WidgetActionType.SetBackgroundExpression -> { if (action.targetId !in nodeIds) errors += "$label writes background on missing node"; checkExpression("$label background", action.value, symbols, arrays, WidgetStateKind.Number) }
                    WidgetActionType.SetTextColorExpression -> {
                        val n = nodesById[action.targetId]
                        if (n == null || n.type == WidgetNodeType.Box) errors += "$label writes unsupported text_color property"
                        checkExpression("$label text_color", action.value, symbols, arrays, WidgetStateKind.Number)
                    }
                    WidgetActionType.SetState -> {
                        val required = stateKinds[action.targetId]
                        if (required == null || required == WidgetStateKind.NumberArray) errors += "$label targets unknown/non-scalar state '${action.targetId}'"
                        else checkExpression("$label state ${action.targetId}", action.value, symbols, arrays, required)
                    }
                    WidgetActionType.SetArrayElement -> {
                        if (action.targetId !in arrayIds) errors += "$label targets unknown array '${action.targetId}'"
                        checkExpression("$label array index", action.auxValue, symbols, arrays, WidgetStateKind.Number)
                        checkExpression("$label array value", action.value, symbols, arrays, WidgetStateKind.Number)
                    }
                    WidgetActionType.CallFunction -> {
                        val fn = functionsById[action.targetId]
                        if (fn == null || fn.params.size != action.arguments.size) errors += "$label calls invalid function '${action.targetId}'"
                        action.arguments.forEachIndexed { i, expr -> checkExpression("$label function arg ${i + 1}", expr, symbols, arrays, WidgetStateKind.Number) }
                    }
                    WidgetActionType.SetDynamicTextExpression -> {
                        checkExpression("$label dynamic target", action.targetId, symbols, arrays, WidgetStateKind.Text)
                        checkExpression("$label dynamic text", action.value, symbols, arrays, WidgetStateKind.Text)
                    }
                    WidgetActionType.SetDynamicBackgroundExpression -> {
                        checkExpression("$label dynamic target", action.targetId, symbols, arrays, WidgetStateKind.Text)
                        checkExpression("$label dynamic background", action.value, symbols, arrays, WidgetStateKind.Number)
                    }
                    WidgetActionType.IfStart -> { checkExpression("$label if", action.value, symbols, arrays, WidgetStateKind.Boolean); ifDepth++; elseSeen += false; if (ifDepth > VeilWidgetLimits.MAX_IF_DEPTH) errors += "$label nests if blocks too deeply" }
                    WidgetActionType.ElseBranch -> {
                        if (ifDepth <= 0) errors += "$label has else without if"
                        else { val last = elseSeen.lastIndex; if (elseSeen[last]) errors += "$label has duplicate else" else elseSeen[last] = true }
                    }
                    WidgetActionType.EndIf -> { if (ifDepth <= 0) errors += "$label has unmatched end-if" else { ifDepth--; elseSeen.removeAt(elseSeen.lastIndex) } }
                    WidgetActionType.NetworkSendInputs -> {
                        if (functionContext) errors += "$label performs network.send inside helper function"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label uses networking without online = public"
                        if (trigger != WidgetTriggerType.Tap) errors += "$label attempts network.send outside tap"
                        if (action.networkInputs.isEmpty() || action.networkInputs.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT) errors += "$label has invalid network input count"
                        val seen = mutableSetOf<String>()
                        action.networkInputs.forEach { sent ->
                            val def = defs[sent.inputId]
                            if (def == null || !seen.add(sent.inputId)) errors += "$label references undeclared/duplicate input '${sent.inputId}'"
                            else when (def.kind) {
                                WidgetInputKind.Number -> if (sent.numberExpression.isNotBlank()) checkExpression("$label input ${sent.inputId}", sent.numberExpression, symbols, arrays, WidgetStateKind.Number) else if (sent.numberValue !in def.minValue..def.maxValue) errors += "$label sends '${sent.inputId}' outside range"
                                WidgetInputKind.Button -> if (sent.numberExpression.isNotBlank() || sent.numberValue != 0) errors += "$label gives a value to button '${sent.inputId}'"
                            }
                        }
                    }
                    WidgetActionType.NetworkSendTextLiteral -> {
                        if (functionContext) errors += "$label performs network.text inside helper function"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label uses networking without online = public"
                        if (trigger != WidgetTriggerType.Tap) errors += "$label sends network text outside tap"
                        checkString("$label network text", action.value, VeilWidgetLimits.MAX_NETWORK_TEXT_BYTES)
                        runCatching { validateWidgetPlainText(action.value) }.onFailure { errors += "$label network text contains disallowed characters" }
                    }
                    WidgetActionType.NetworkSendTextFrom -> {
                        if (functionContext) errors += "$label performs network.text_from inside helper function"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label uses networking without online = public"
                        if (trigger != WidgetTriggerType.Tap) errors += "$label sends network text outside tap"
                        if (program.nodes.firstOrNull { it.id == action.targetId }?.type != WidgetNodeType.TextInput) errors += "$label text source is not a TextInput"
                    }
                    WidgetActionType.NetworkOpenInvitation -> {
                        if (functionContext || trigger != WidgetTriggerType.Tap) errors += "$label opens invitation outside tap"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label opens invite without online = public"
                    }
                    WidgetActionType.NetworkAccept, WidgetActionType.NetworkReject -> if (functionContext || trigger !in setOf(WidgetTriggerType.NetworkInput, WidgetTriggerType.NetworkAction)) errors += "$label accepts/rejects outside input/action proposal"
                    WidgetActionType.NetworkAcceptInvite, WidgetActionType.NetworkDeclineInvite -> if (functionContext || trigger !in setOf(WidgetTriggerType.NetworkInvite, WidgetTriggerType.Tap)) errors += "$label decides invitation outside invite/tap"
                    WidgetActionType.NetworkBeginAction, WidgetActionType.NetworkEndAction -> {
                        if (functionContext || trigger != WidgetTriggerType.Tap) errors += "$label controls grouped action outside tap"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label grouped action requires online = public"
                    }
                    WidgetActionType.NetworkRoll -> {
                        if (functionContext || trigger !in setOf(WidgetTriggerType.Tap, WidgetTriggerType.NetworkRandomReady)) errors += "$label rolls outside tap/random_ready"
                        val sides = action.value.toIntOrNull(); if (sides == null || sides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) errors += "$label has invalid dice sides"
                        if (program.onlineMode != WidgetOnlineMode.Public) errors += "$label roll requires online = public"
                    }
                }
            }
            if (ifDepth != 0) errors += "$label has an unclosed if block"
        }

        program.functions.forEach { fn ->
            val symbols = linkedMapOf<String, WidgetStateKind>(); symbols.putAll(stateKinds); fn.params.forEach { symbols[it] = WidgetStateKind.Number }
            verifyActions(fn.actions, null, symbols, arrayIds, true, "function ${fn.id}")
        }

        val graph = program.functions.associate { fn -> fn.id to fn.actions.filter { it.type == WidgetActionType.CallFunction }.map { it.targetId } }
        fun reaches(start: String, node: String, path: Set<String>): Boolean {
            if (node in path) return node == start
            val nextPath = path + node
            return graph[node].orEmpty().any { it == start || (it in graph && reaches(start, it, nextPath)) }
        }
        program.functions.forEach { if (reaches(it.id, it.id, emptySet())) errors += "recursive helper-function cycle at '${it.id}'" }

        var timerCount = 0
        program.events.forEachIndexed { index, event ->
            if (event.actions.size > VeilWidgetLimits.MAX_ACTIONS_PER_EVENT) errors += "event $index has too many actions"
            when (event.trigger) {
                WidgetTriggerType.Tap -> if (event.targetId !in nodeIds) errors += "event $index has missing tap target"
                WidgetTriggerType.Every -> { timerCount++; if (event.intervalMs !in VeilWidgetLimits.MIN_TIMER_MS..VeilWidgetLimits.MAX_TIMER_MS) errors += "event $index has invalid timer interval" }
                WidgetTriggerType.NetworkInput, WidgetTriggerType.NetworkCommitted, WidgetTriggerType.NetworkAction, WidgetTriggerType.NetworkActionCommitted -> {
                    if (program.onlineMode != WidgetOnlineMode.Public) errors += "event $index has network handler without online = public"
                    if (event.networkInputIds.isEmpty() || event.networkInputIds.size > VeilWidgetLimits.MAX_NETWORK_INPUTS_PER_EVENT || event.networkInputIds.distinct().size != event.networkInputIds.size) errors += "event $index has invalid network input signature"
                    event.networkInputIds.forEach { if (it !in defs) errors += "event $index listens for undeclared input '$it'" }
                }
                WidgetTriggerType.NetworkText -> {
                    if (program.onlineMode != WidgetOnlineMode.Public) errors += "event $index has text handler without online = public"
                    if (!shortIdPattern.matches(event.eventVariable) || event.eventVariable in stateIds || event.eventVariable in nodeIds) errors += "event $index has invalid text variable"
                }
                WidgetTriggerType.NetworkInvite, WidgetTriggerType.NetworkInviteAccepted, WidgetTriggerType.NetworkSessionReady, WidgetTriggerType.NetworkRandomReady -> if (program.onlineMode != WidgetOnlineMode.Public) errors += "event $index has network handler without online = public"
                WidgetTriggerType.NetworkRoll -> {
                    if (program.onlineMode != WidgetOnlineMode.Public) errors += "event $index has roll handler without online = public"
                    if (event.networkRollSides !in 2..VeilWidgetLimits.MAX_DICE_SIDES) errors += "event $index has invalid roll sides"
                    if (!shortIdPattern.matches(event.eventVariable) || event.eventVariable in stateIds || event.eventVariable in nodeIds) errors += "event $index has invalid roll variable"
                }
            }
            val (symbols, arrays) = symbolsFor(event)
            verifyActions(event.actions, event.trigger, symbols, arrays, false, "event $index")
        }
        if (timerCount > VeilWidgetLimits.MAX_TIMER_EVENTS) errors += "too many repeating timers"

        val expectedCapabilities = mutableSetOf<WidgetCapability>()
        if (program.onlineMode == WidgetOnlineMode.Public) expectedCapabilities += WidgetCapability.PublicNetwork
        if (program.events.any { it.trigger == WidgetTriggerType.Every || it.actions.any { a -> a.type == WidgetActionType.SetTextTime } } || program.functions.any { fn -> fn.actions.any { it.type == WidgetActionType.SetTextTime } }) expectedCapabilities += WidgetCapability.Time
        if (program.capabilities != expectedCapabilities) errors += "capability set does not match executable program"

        return if (errors.isEmpty()) WidgetVerificationResult(true, program) else WidgetVerificationResult(false, errors = errors.distinct())
    }
}
