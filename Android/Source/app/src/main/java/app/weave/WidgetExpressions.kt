package app.weave

/**
 * Tiny side-effect-free expression engine for widget state and `if` conditions.
 *
 * Expressions can see only values supplied by the runtime: scalar state, bounded numeric arrays,
 * helper-function parameters and read-only event/host metadata. There is no reflection, eval,
 * native API access, file/network access, object property access or function invocation here.
 */
sealed class WidgetValue {
    data class Number(val value: Long) : WidgetValue()
    data class BooleanValue(val value: Boolean) : WidgetValue()
    data class Text(val value: String) : WidgetValue()

    fun kind(): WidgetStateKind = when (this) {
        is Number -> WidgetStateKind.Number
        is BooleanValue -> WidgetStateKind.Boolean
        is Text -> WidgetStateKind.Text
    }

    fun displayText(): String = when (this) {
        is Number -> value.toString()
        is BooleanValue -> value.toString()
        is Text -> value
    }
}

object WidgetExpressions {
    data class Check(val ok: Boolean, val kind: WidgetStateKind? = null, val error: String = "")

    private enum class TokenKind { Number, Text, Identifier, True, False, Op, LParen, RParen, LBracket, RBracket, End }
    private data class Token(val kind: TokenKind, val text: String)

    private sealed class Expr {
        data class Literal(val value: WidgetValue) : Expr()
        data class Variable(val name: String) : Expr()
        data class Index(val name: String, val index: Expr) : Expr()
        data class Unary(val op: String, val child: Expr) : Expr()
        data class Binary(val left: Expr, val op: String, val right: Expr) : Expr()
    }

    private class Parser(private val tokens: List<Token>) {
        private var at = 0
        private fun current() = tokens[at]
        private fun take(): Token = tokens[at++]
        private fun matchOp(vararg names: String): String? {
            val t = current()
            if (t.kind == TokenKind.Op && t.text in names) { at++; return t.text }
            return null
        }

        fun parse(): Expr {
            val result = parseOr()
            require(current().kind == TokenKind.End) { "unexpected '${current().text}'" }
            return result
        }

        private fun parseOr(): Expr {
            var e = parseAnd()
            while (true) { val op = matchOp("||", "or") ?: return e; e = Expr.Binary(e, op, parseAnd()) }
        }
        private fun parseAnd(): Expr {
            var e = parseEquality()
            while (true) { val op = matchOp("&&", "and") ?: return e; e = Expr.Binary(e, op, parseEquality()) }
        }
        private fun parseEquality(): Expr {
            var e = parseComparison()
            while (true) { val op = matchOp("==", "!=") ?: return e; e = Expr.Binary(e, op, parseComparison()) }
        }
        private fun parseComparison(): Expr {
            var e = parseAdditive()
            while (true) { val op = matchOp("<", "<=", ">", ">=") ?: return e; e = Expr.Binary(e, op, parseAdditive()) }
        }
        private fun parseAdditive(): Expr {
            var e = parseMultiplicative()
            while (true) { val op = matchOp("+", "-") ?: return e; e = Expr.Binary(e, op, parseMultiplicative()) }
        }
        private fun parseMultiplicative(): Expr {
            var e = parseUnary()
            while (true) { val op = matchOp("*", "/", "%") ?: return e; e = Expr.Binary(e, op, parseUnary()) }
        }
        private fun parseUnary(): Expr {
            val op = matchOp("!", "not", "-")
            return if (op != null) Expr.Unary(op, parseUnary()) else parsePrimary()
        }
        private fun parsePrimary(): Expr {
            val t = take()
            return when (t.kind) {
                TokenKind.Number -> Expr.Literal(WidgetValue.Number(t.text.toLong()))
                TokenKind.Text -> Expr.Literal(WidgetValue.Text(t.text))
                TokenKind.True -> Expr.Literal(WidgetValue.BooleanValue(true))
                TokenKind.False -> Expr.Literal(WidgetValue.BooleanValue(false))
                TokenKind.Identifier -> {
                    if (current().kind == TokenKind.LBracket) {
                        take()
                        val index = parseOr()
                        require(take().kind == TokenKind.RBracket) { "expected ']'" }
                        Expr.Index(t.text, index)
                    } else Expr.Variable(t.text)
                }
                TokenKind.LParen -> {
                    val inner = parseOr(); require(take().kind == TokenKind.RParen) { "expected ')'" }; inner
                }
                else -> error("expected a value")
            }
        }
    }

    private fun tokenize(source: String): List<Token> {
        require(source.toByteArray(Charsets.UTF_8).size <= VeilWidgetLimits.MAX_EXPRESSION_BYTES) { "expression is too long" }
        val out = mutableListOf<Token>()
        var i = 0
        fun add(token: Token) { require(out.size < VeilWidgetLimits.MAX_EXPRESSION_TOKENS) { "expression has too many tokens" }; out += token }
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace()) { i++; continue }
            when {
                c == '(' -> { add(Token(TokenKind.LParen, "(")); i++ }
                c == ')' -> { add(Token(TokenKind.RParen, ")")); i++ }
                c == '[' -> { add(Token(TokenKind.LBracket, "[")); i++ }
                c == ']' -> { add(Token(TokenKind.RBracket, "]")); i++ }
                c == '"' -> {
                    i++
                    val s = StringBuilder(); var closed = false
                    while (i < source.length) {
                        val q = source[i++]
                        if (q == '"') { closed = true; break }
                        if (q == '\\') {
                            require(i < source.length) { "unfinished string escape" }
                            val e = source[i++]
                            s.append(when (e) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '"' -> '"'; '\\' -> '\\'; else -> error("unsupported string escape") })
                        } else s.append(q)
                    }
                    require(closed) { "unterminated string" }
                    require(s.toString().toByteArray(Charsets.UTF_8).size <= VeilWidgetLimits.MAX_STATE_TEXT_BYTES) { "text value is too large" }
                    add(Token(TokenKind.Text, s.toString()))
                }
                c.isDigit() -> {
                    val start = i; while (i < source.length && source[i].isDigit()) i++
                    add(Token(TokenKind.Number, source.substring(start, i)))
                }
                c.isLetter() || c == '_' -> {
                    val start = i; i++
                    // Dots are allowed only inside read-only host metadata names such as network.my_player.
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.')) i++
                    val word = source.substring(start, i)
                    require(!word.endsWith('.') && !word.contains("..")) { "invalid identifier '$word'" }
                    when (word) {
                        "true" -> add(Token(TokenKind.True, word))
                        "false" -> add(Token(TokenKind.False, word))
                        "and", "or", "not" -> add(Token(TokenKind.Op, word))
                        else -> add(Token(TokenKind.Identifier, word))
                    }
                }
                else -> {
                    val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                    if (two in listOf("==", "!=", "<=", ">=", "&&", "||")) { add(Token(TokenKind.Op, two)); i += 2 }
                    else if (c in charArrayOf('+', '-', '*', '/', '%', '<', '>', '!')) { add(Token(TokenKind.Op, c.toString())); i++ }
                    else error("unsupported character '$c'")
                }
            }
        }
        out += Token(TokenKind.End, "")
        return out
    }

    private const val MAX_PARSE_CACHE = 512
    private val parseCache = object : LinkedHashMap<String, Expr>(MAX_PARSE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Expr>?): Boolean = size > MAX_PARSE_CACHE
    }

    @Synchronized
    private fun parse(source: String): Expr {
        val canonical = source.trim()
        parseCache[canonical]?.let { return it }
        val parsed = Parser(tokenize(canonical)).parse()
        parseCache[canonical] = parsed
        return parsed
    }

    fun check(
        source: String,
        symbols: Map<String, WidgetStateKind>,
        arrays: Set<String> = emptySet(),
    ): Check = runCatching { Check(true, infer(parse(source), symbols, arrays)) }
        .getOrElse { Check(false, error = it.message ?: "invalid expression") }

    fun evaluate(
        source: String,
        values: Map<String, WidgetValue>,
        arrays: Map<String, List<Long>> = emptyMap(),
    ): WidgetValue = eval(parse(source), values, arrays)

    fun literalValue(source: String): WidgetValue = evaluate(source, emptyMap(), emptyMap())

    private fun infer(expr: Expr, symbols: Map<String, WidgetStateKind>, arrays: Set<String>): WidgetStateKind = when (expr) {
        is Expr.Literal -> expr.value.kind()
        is Expr.Variable -> {
            val kind = symbols[expr.name] ?: error("unknown variable '${expr.name}'")
            require(kind != WidgetStateKind.NumberArray) { "array '${expr.name}' requires an index" }
            kind
        }
        is Expr.Index -> {
            require(expr.name in arrays || symbols[expr.name] == WidgetStateKind.NumberArray) { "unknown numeric array '${expr.name}'" }
            require(infer(expr.index, symbols, arrays) == WidgetStateKind.Number) { "array index must be a number" }
            WidgetStateKind.Number
        }
        is Expr.Unary -> {
            val child = infer(expr.child, symbols, arrays)
            when (expr.op) {
                "!", "not" -> { require(child == WidgetStateKind.Boolean) { "'${expr.op}' requires a boolean" }; WidgetStateKind.Boolean }
                "-" -> { require(child == WidgetStateKind.Number) { "unary '-' requires a number" }; WidgetStateKind.Number }
                else -> error("unknown unary operator")
            }
        }
        is Expr.Binary -> {
            val left = infer(expr.left, symbols, arrays); val right = infer(expr.right, symbols, arrays)
            when (expr.op) {
                "+" -> if (left == WidgetStateKind.Text || right == WidgetStateKind.Text) WidgetStateKind.Text else { require(left == WidgetStateKind.Number && right == WidgetStateKind.Number) { "'+' requires numbers or text" }; WidgetStateKind.Number }
                "-", "*", "/", "%" -> { require(left == WidgetStateKind.Number && right == WidgetStateKind.Number) { "'${expr.op}' requires numbers" }; WidgetStateKind.Number }
                "<", "<=", ">", ">=" -> { require(left == WidgetStateKind.Number && right == WidgetStateKind.Number) { "'${expr.op}' requires numbers" }; WidgetStateKind.Boolean }
                "==", "!=" -> { require(left == right) { "'${expr.op}' compares values of the same type" }; WidgetStateKind.Boolean }
                "&&", "and", "||", "or" -> { require(left == WidgetStateKind.Boolean && right == WidgetStateKind.Boolean) { "'${expr.op}' requires booleans" }; WidgetStateKind.Boolean }
                else -> error("unknown binary operator")
            }
        }
    }

    private fun eval(expr: Expr, values: Map<String, WidgetValue>, arrays: Map<String, List<Long>>): WidgetValue = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.Variable -> values[expr.name] ?: error("unknown variable '${expr.name}'")
        is Expr.Index -> {
            val list = arrays[expr.name] ?: error("unknown numeric array '${expr.name}'")
            val index = (eval(expr.index, values, arrays) as? WidgetValue.Number ?: error("array index must be numeric")).value
            require(index in 0 until list.size.toLong()) { "array index out of bounds" }
            WidgetValue.Number(list[index.toInt()])
        }
        is Expr.Unary -> when (expr.op) {
            "!", "not" -> WidgetValue.BooleanValue(!(eval(expr.child, values, arrays) as? WidgetValue.BooleanValue ?: error("boolean required")).value)
            "-" -> WidgetValue.Number(Math.negateExact((eval(expr.child, values, arrays) as? WidgetValue.Number ?: error("number required")).value))
            else -> error("unknown unary operator")
        }
        is Expr.Binary -> {
            if (expr.op == "&&" || expr.op == "and") {
                val l = (eval(expr.left, values, arrays) as? WidgetValue.BooleanValue ?: error("boolean required")).value
                if (!l) WidgetValue.BooleanValue(false) else WidgetValue.BooleanValue((eval(expr.right, values, arrays) as? WidgetValue.BooleanValue ?: error("boolean required")).value)
            } else if (expr.op == "||" || expr.op == "or") {
                val l = (eval(expr.left, values, arrays) as? WidgetValue.BooleanValue ?: error("boolean required")).value
                if (l) WidgetValue.BooleanValue(true) else WidgetValue.BooleanValue((eval(expr.right, values, arrays) as? WidgetValue.BooleanValue ?: error("boolean required")).value)
            } else {
                val l = eval(expr.left, values, arrays); val r = eval(expr.right, values, arrays)
                when (expr.op) {
                    "+" -> when {
                        l is WidgetValue.Text || r is WidgetValue.Text -> WidgetValue.Text(l.displayText() + r.displayText())
                        l is WidgetValue.Number && r is WidgetValue.Number -> WidgetValue.Number(Math.addExact(l.value, r.value))
                        else -> error("invalid '+' operands")
                    }
                    "-" -> WidgetValue.Number(Math.subtractExact((l as WidgetValue.Number).value, (r as WidgetValue.Number).value))
                    "*" -> WidgetValue.Number(Math.multiplyExact((l as WidgetValue.Number).value, (r as WidgetValue.Number).value))
                    "/" -> { val rv=(r as WidgetValue.Number).value; require(rv != 0L) { "division by zero" }; WidgetValue.Number((l as WidgetValue.Number).value / rv) }
                    "%" -> { val rv=(r as WidgetValue.Number).value; require(rv != 0L) { "division by zero" }; WidgetValue.Number((l as WidgetValue.Number).value % rv) }
                    "<" -> WidgetValue.BooleanValue((l as WidgetValue.Number).value < (r as WidgetValue.Number).value)
                    "<=" -> WidgetValue.BooleanValue((l as WidgetValue.Number).value <= (r as WidgetValue.Number).value)
                    ">" -> WidgetValue.BooleanValue((l as WidgetValue.Number).value > (r as WidgetValue.Number).value)
                    ">=" -> WidgetValue.BooleanValue((l as WidgetValue.Number).value >= (r as WidgetValue.Number).value)
                    "==" -> WidgetValue.BooleanValue(l == r)
                    "!=" -> WidgetValue.BooleanValue(l != r)
                    else -> error("unknown operator")
                }
            }
        }
    }
}
