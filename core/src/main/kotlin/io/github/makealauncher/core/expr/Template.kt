package io.github.makealauncher.core.expr

/**
 * Text with `{...}` expressions in it, such as `Battery {battery.level}%`.
 *
 * Phase 1 uses this tiny built-in expression language: a `namespace.member` reference,
 * optionally called with literal arguments, e.g. `{time.format("HH:mm")}`. Phase 2 swaps
 * the evaluator for Luau while keeping the same template syntax and dependency tracking.
 *
 * Write `{{` and `}}` for literal braces. A template that is exactly one expression keeps
 * that expression's type (a number stays a number); anything else becomes text.
 */
class Template private constructor(val source: String, private val parts: List<Part>) {

    private sealed class Part {
        class Text(val text: String) : Part()
        class Code(val expr: Expr) : Part()
    }

    /** True when the template has no expressions, so its value never changes. */
    val isConstant: Boolean = parts.none { it is Part.Code }

    fun evaluate(scope: EvalScope): Any? {
        if (parts.size == 1) {
            val only = parts[0]
            return when (only) {
                is Part.Text -> only.text
                is Part.Code -> only.expr.eval(scope)
            }
        }
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Text -> sb.append(p.text)
                is Part.Code -> sb.append(stringify(p.expr.eval(scope)))
            }
        }
        return sb.toString()
    }

    override fun toString(): String = source

    companion object {
        /** True if [text] contains at least one `{...}` expression (and not just `{{`). */
        fun hasCode(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                if (text[i] == '{') {
                    if (i + 1 < text.length && text[i + 1] == '{') {
                        i += 2
                        continue
                    }
                    return true
                }
                i++
            }
            return false
        }

        fun parse(text: String, schema: Schema): Template {
            val parts = ArrayList<Part>()
            val lit = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '{' && i + 1 < text.length && text[i + 1] == '{' -> {
                        lit.append('{')
                        i += 2
                    }
                    c == '}' && i + 1 < text.length && text[i + 1] == '}' -> {
                        lit.append('}')
                        i += 2
                    }
                    c == '{' -> {
                        val end = findClose(text, i + 1)
                        if (end < 0) throw ExprException("missing '}' for the '{' at column ${i + 1}", i)
                        if (lit.isNotEmpty()) {
                            parts += Part.Text(lit.toString())
                            lit.clear()
                        }
                        val code = text.substring(i + 1, end)
                        parts += Part.Code(ExprParser(code, i + 1, schema).parseAll())
                        i = end + 1
                    }
                    c == '}' -> throw ExprException("unmatched '}' at column ${i + 1}; write '}}' for a literal brace", i)
                    else -> {
                        lit.append(c)
                        i++
                    }
                }
            }
            if (lit.isNotEmpty() || parts.isEmpty()) parts += Part.Text(lit.toString())
            return Template(text, parts)
        }

        /** Finds the '}' closing an expression, skipping braces inside string literals. */
        private fun findClose(text: String, from: Int): Int {
            var i = from
            while (i < text.length) {
                val c = text[i]
                if (c == '"' || c == '\'') {
                    i++
                    while (i < text.length && text[i] != c) {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    continue
                }
                if (c == '}') return i
                if (c == '{') return -1
                i++
            }
            return -1
        }

        fun stringify(v: Any?): String = when (v) {
            null -> ""
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            is Float -> stringify(v.toDouble())
            else -> v.toString()
        }
    }
}

class ExprException(message: String, val offset: Int) : Exception(message)

/** Looks up `namespace.member` values while a template evaluates. Reads record dependencies. */
fun interface EvalScope {
    /** [args] is null for a property read and a list for a call. */
    fun resolve(namespace: String, member: String, args: List<Any?>?): Any?
}

/** The names a template may use, checked when the preset loads rather than when it runs. */
class Schema(val namespaces: Map<String, NamespaceSchema>)

class NamespaceSchema(
    val properties: Set<String>,
    val functions: Map<String, FunctionSpec> = emptyMap(),
)

class FunctionSpec(
    val arity: IntRange,
    /** Checks literal arguments at load time; returns an error message or null. */
    val validate: ((List<Any?>) -> String?)? = null,
)

internal sealed class Expr {
    abstract fun eval(scope: EvalScope): Any?

    class Literal(val value: Any?) : Expr() {
        override fun eval(scope: EvalScope): Any? = value
    }

    class Ref(val namespace: String, val member: String, val args: List<Expr>?) : Expr() {
        override fun eval(scope: EvalScope): Any? =
            scope.resolve(namespace, member, args?.map { it.eval(scope) })
    }
}

private class ExprParser(private val s: String, private val base: Int, private val schema: Schema) {
    private var i = 0

    fun parseAll(): Expr {
        ws()
        if (i >= s.length) throw err("empty expression '{}'")
        val e = expr()
        ws()
        if (i < s.length) throw err("unexpected '${s.substring(i)}'")
        return e
    }

    private fun err(msg: String) = ExprException(msg, base + i)

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expr(): Expr {
        ws()
        if (i >= s.length) throw err("expected a value")
        val c = s[i]
        return when {
            c == '"' || c == '\'' -> Expr.Literal(string())
            c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit()) -> Expr.Literal(number())
            c.isLetter() || c == '_' -> reference()
            else -> throw err("unexpected '$c'")
        }
    }

    private fun ident(): String {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        if (start == i) throw err("expected a name")
        return s.substring(start, i)
    }

    private fun reference(): Expr {
        val start = i
        val first = ident()
        when (first) {
            "true" -> return Expr.Literal(true)
            "false" -> return Expr.Literal(false)
            "null" -> return Expr.Literal(null)
        }
        ws()
        if (i >= s.length || s[i] != '.') {
            throw ExprException("unknown name '$first'; expected something like 'battery.level' or 'time.format(\"HH:mm\")'", base + start)
        }
        i++
        ws()
        val member = ident()
        val ns = schema.namespaces[first]
            ?: throw ExprException("unknown data source '$first'; available: ${schema.namespaces.keys.sorted().joinToString()}", base + start)
        ws()
        if (i < s.length && s[i] == '(') {
            i++
            val args = ArrayList<Expr>()
            ws()
            if (i < s.length && s[i] == ')') {
                i++
            } else {
                while (true) {
                    args += expr()
                    ws()
                    if (i >= s.length) throw err("missing ')'")
                    if (s[i] == ',') {
                        i++
                        continue
                    }
                    if (s[i] == ')') {
                        i++
                        break
                    }
                    throw err("expected ',' or ')'")
                }
            }
            val fn = ns.functions[member]
                ?: throw ExprException(
                    if (member in ns.properties) "'$first.$member' is a value, not a function; drop the parentheses"
                    else "unknown function '$first.$member'" + suggest(member, ns.functions.keys),
                    base + start,
                )
            if (args.size !in fn.arity) {
                throw ExprException("'$first.$member' takes ${describe(fn.arity)}, got ${args.size}", base + start)
            }
            if (args.all { it is Expr.Literal }) {
                fn.validate?.invoke(args.map { (it as Expr.Literal).value })?.let { throw ExprException(it, base + start) }
            }
            return Expr.Ref(first, member, args)
        }
        if (member !in ns.properties) {
            throw ExprException(
                if (member in ns.functions) "'$first.$member' is a function; call it like '$first.$member(...)'"
                else "unknown value '$first.$member'" + suggest(member, ns.properties),
                base + start,
            )
        }
        return Expr.Ref(first, member, null)
    }

    private fun string(): String {
        val q = s[i++]
        val sb = StringBuilder()
        while (i < s.length && s[i] != q) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(s[i++])
            }
        }
        if (i >= s.length) throw err("missing closing $q")
        i++
        return sb.toString()
    }

    private fun number(): Number {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i].isDigit()) i++
        var isDouble = false
        if (i < s.length && s[i] == '.') {
            isDouble = true
            i++
            while (i < s.length && s[i].isDigit()) i++
        }
        val t = s.substring(start, i)
        return if (isDouble) t.toDouble() else (t.toIntOrNull() ?: t.toDouble())
    }

    private fun describe(r: IntRange) = when {
        r.first == r.last -> "${r.first} argument" + if (r.first == 1) "" else "s"
        else -> "${r.first} to ${r.last} arguments"
    }
}

/** " (did you mean 'x'?)" when a close match exists, else "". */
fun suggest(word: String, candidates: Collection<String>): String {
    var best: String? = null
    var bestD = Int.MAX_VALUE
    for (c in candidates) {
        val d = editDistance(word.lowercase(), c.lowercase())
        if (d < bestD) {
            bestD = d
            best = c
        }
    }
    return if (best != null && bestD <= maxOf(1, word.length / 3)) " (did you mean '$best'?)" else ""
}

private fun editDistance(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = tmp
        }
    }
    return dp[b.length]
}
