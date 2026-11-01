package io.github.makealauncher.core.yaml

/**
 * A strict, dependency-free parser for the subset of YAML that presets use.
 *
 * Supported: block mappings and sequences (including `key:` followed by a same-indent list),
 * plain / 'single' / "double" quoted scalars, flow sequences `[a, b]` and mappings `{a: 1}`
 * on one line, block scalars (`|`, `|-`, `|+`, `>`, `>-`, `>+`), comments, one leading `---`.
 *
 * Rejected with a clear error: tabs in indentation, anchors (`&`), aliases (`*`), tags (`!`),
 * duplicate keys, multi-line plain values, multiple documents.
 *
 * Keeping the format small means the phone editor can always rewrite a file it loaded,
 * and every node carries its line number for error messages.
 */
object Yaml {
    fun parse(text: String): YamlNode = Parser(text).parseDocument()
}

private class Parser(text: String) {
    private val raw: List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    private var pos = 0
    private var override: Line? = null
    private var sawContent = false

    private data class Line(val number: Int, val indent: Int, val content: String)

    fun parseDocument(): YamlNode {
        val first = peek() ?: return YamlMap(emptyMap(), emptyMap(), 1)
        val node = parseBlock(first.indent)
        peek()?.let {
            if (it.content == "---") throw YamlException("multiple documents in one file aren't supported", it.number)
            throw YamlException("unexpected content; check the indentation of this line", it.number)
        }
        return node
    }

    // ---- line handling -------------------------------------------------------------------

    private fun peek(): Line? {
        override?.let { return it }
        while (pos < raw.size) {
            val r = raw[pos]
            var i = 0
            while (i < r.length && r[i] == ' ') i++
            if (i < r.length && r[i] == '\t') {
                throw YamlException("tabs aren't allowed for indentation; use spaces", pos + 1)
            }
            val content = stripComment(r.substring(i)).trimEnd()
            if (content.isEmpty()) {
                pos++
                continue
            }
            if (i == 0 && content == "---" && !sawContent) {
                pos++
                sawContent = true
                continue
            }
            sawContent = true
            return Line(pos + 1, i, content)
        }
        return null
    }

    private fun consume() {
        if (override != null) override = null else pos++
    }

    // ---- block structure -----------------------------------------------------------------

    private fun parseBlock(indent: Int): YamlNode {
        val l = peek()!!
        return when {
            isSeqItem(l.content) -> parseSeq(indent)
            findMapColon(l.content) >= 0 -> parseMap(indent)
            else -> {
                consume()
                if (l.content.startsWith("|") || l.content.startsWith(">")) {
                    parseBlockScalar(l.content, indent - 1, l.number)
                } else {
                    val v = parseInline(l.content, l.number)
                    rejectDeeper(indent, "unexpected indentation")
                    v
                }
            }
        }
    }

    private fun parseMap(indent: Int): YamlMap {
        val entries = LinkedHashMap<String, YamlNode>()
        val keyLines = LinkedHashMap<String, Int>()
        val startLine = peek()!!.number
        while (true) {
            val l = peek() ?: break
            if (l.indent < indent) break
            if (l.indent > indent) throw YamlException("unexpected indentation", l.number)
            if (isSeqItem(l.content)) {
                throw YamlException("expected 'key: value' but found a list item", l.number)
            }
            val colon = findMapColon(l.content)
            if (colon < 0) throw YamlException("expected 'key: value'", l.number)
            val key = parseKey(l.content.substring(0, colon), l.number)
            if (key in entries) throw YamlException("duplicate key '$key'", l.number)
            val rest = l.content.substring(colon + 1).trim()
            consume()
            keyLines[key] = l.number
            entries[key] = parseValue(rest, indent, l.number, allowCompactSeq = true)
        }
        return YamlMap(entries, keyLines, startLine)
    }

    private fun parseSeq(indent: Int): YamlList {
        val items = ArrayList<YamlNode>()
        val startLine = peek()!!.number
        while (true) {
            val l = peek() ?: break
            if (l.indent < indent) break
            if (l.indent > indent) throw YamlException("unexpected indentation", l.number)
            if (!isSeqItem(l.content)) break
            var j = 1
            while (j < l.content.length && l.content[j] == ' ') j++
            val rest = l.content.substring(j)
            consume()
            if (rest.isNotEmpty() && (isSeqItem(rest) || findMapColon(rest) >= 0)) {
                // "- key: value" starts a mapping whose keys line up after the dash.
                override = Line(l.number, indent + j, rest)
                items += parseBlock(indent + j)
            } else {
                items += parseValue(rest, indent, l.number, allowCompactSeq = false)
            }
        }
        return YamlList(items, startLine)
    }

    /** Parses what follows `key:` or `- `. [parentIndent] is the indent of that key or dash. */
    private fun parseValue(rest: String, parentIndent: Int, line: Int, allowCompactSeq: Boolean): YamlNode {
        if (rest.isEmpty()) {
            val n = peek()
            return when {
                n != null && n.indent > parentIndent -> parseBlock(n.indent)
                allowCompactSeq && n != null && n.indent == parentIndent && isSeqItem(n.content) -> parseSeq(parentIndent)
                else -> YamlScalar("", quoted = false, line = line)
            }
        }
        if (rest[0] == '|' || rest[0] == '>') return parseBlockScalar(rest, parentIndent, line)
        val v = parseInline(rest, line)
        rejectDeeper(parentIndent, "unexpected indentation (values can't continue on the next line; use | for multi-line text)")
        return v
    }

    private fun rejectDeeper(indent: Int, message: String) {
        peek()?.let { if (it.indent > indent) throw YamlException(message, it.number) }
    }

    private fun parseBlockScalar(header: String, parentIndent: Int, line: Int): YamlScalar {
        val style = header[0]
        val chomp = header.getOrNull(1)
        if (header.length > 2 || (chomp != null && chomp != '-' && chomp != '+')) {
            throw YamlException("unsupported block scalar header '$header'; use |, |-, |+, >, >- or >+", line)
        }
        val collected = ArrayList<String>()
        while (pos < raw.size) {
            val r = raw[pos]
            if (r.isBlank()) {
                collected += ""
                pos++
                continue
            }
            var i = 0
            while (i < r.length && r[i] == ' ') i++
            if (i <= parentIndent) break
            collected += r
            pos++
        }
        var trailingBlank = 0
        while (collected.isNotEmpty() && collected.last().isEmpty()) {
            collected.removeAt(collected.size - 1)
            trailingBlank++
        }
        val contentIndent = collected.firstOrNull { it.isNotEmpty() }?.let { s -> s.length - s.trimStart(' ').length } ?: 0
        val lines = collected.map { s ->
            if (s.isEmpty()) "" else {
                val ind = s.length - s.trimStart(' ').length
                if (ind < contentIndent) throw YamlException("block text is indented less than its first line", line)
                s.substring(contentIndent)
            }
        }
        val body = if (style == '|') {
            lines.joinToString("\n")
        } else {
            val sb = StringBuilder()
            var prevBlank = true
            for (s in lines) {
                if (s.isEmpty()) {
                    sb.append('\n')
                    prevBlank = true
                } else {
                    if (!prevBlank) sb.append(' ')
                    sb.append(s)
                    prevBlank = false
                }
            }
            sb.toString()
        }
        val text = when (chomp) {
            '-' -> body
            '+' -> body + "\n".repeat(1 + trailingBlank)
            else -> if (body.isEmpty()) "" else body + "\n"
        }
        return YamlScalar(text, quoted = true, line = line)
    }

    // ---- scalars, keys and flow collections ----------------------------------------------

    private fun parseKey(s: String, line: Int): String {
        val t = s.trim()
        if (t.isEmpty()) throw YamlException("empty key", line)
        return when (t[0]) {
            '"', '\'' -> {
                val (value, end) = readQuoted(t, 0, line)
                if (end != t.length) throw YamlException("unexpected text after quoted key", line)
                value
            }
            '?', '&', '*', '!', '[', '{' -> throw YamlException("unsupported key syntax '$t'", line)
            else -> t
        }
    }

    private fun parseInline(s: String, line: Int): YamlNode {
        val t = s.trim()
        return when (t[0]) {
            '[', '{' -> FlowParser(t, line).parseAll()
            '"', '\'' -> {
                val (value, end) = readQuoted(t, 0, line)
                if (end != t.length) throw YamlException("unexpected text after quoted value", line)
                YamlScalar(value, quoted = true, line = line)
            }
            '&' -> throw YamlException("anchors (&) aren't supported in presets", line)
            '*' -> throw YamlException("aliases (*) aren't supported in presets", line)
            '!' -> throw YamlException("tags (!) aren't supported in presets", line)
            '@', '`' -> throw YamlException("a value can't start with '${t[0]}'; put it in quotes", line)
            else -> YamlScalar(t, quoted = false, line = line)
        }
    }

    private inner class FlowParser(private val s: String, private val line: Int) {
        private var i = 0

        fun parseAll(): YamlNode {
            val v = value()
            ws()
            if (i != s.length) throw YamlException("unexpected text after '${s.substring(0, i)}'", line)
            return v
        }

        private fun ws() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun value(): YamlNode {
            ws()
            if (i >= s.length) throw YamlException("unfinished list or mapping (they must close on the same line)", line)
            return when (s[i]) {
                '[' -> list()
                '{' -> map()
                '"', '\'' -> {
                    val (v, end) = readQuoted(s, i, line)
                    i = end
                    YamlScalar(v, quoted = true, line = line)
                }
                '&', '*', '!' -> throw YamlException("anchors, aliases and tags aren't supported in presets", line)
                else -> {
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '}') i++
                    YamlScalar(s.substring(start, i).trim(), quoted = false, line = line)
                }
            }
        }

        private fun list(): YamlList {
            i++ // [
            val items = ArrayList<YamlNode>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return YamlList(items, line)
            }
            while (true) {
                items += value()
                ws()
                if (i >= s.length) throw YamlException("missing ']'", line)
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return YamlList(items, line)
                    }
                    else -> throw YamlException("expected ',' or ']'", line)
                }
            }
        }

        private fun map(): YamlMap {
            i++ // {
            val entries = LinkedHashMap<String, YamlNode>()
            val keyLines = LinkedHashMap<String, Int>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return YamlMap(entries, keyLines, line)
            }
            while (true) {
                ws()
                val key: String = if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val (k, end) = readQuoted(s, i, line)
                    i = end
                    k
                } else {
                    val start = i
                    while (i < s.length && s[i] != ':' && s[i] != ',' && s[i] != '}') i++
                    s.substring(start, i).trim()
                }
                ws()
                if (key.isEmpty()) throw YamlException("empty key in '{...}'", line)
                if (key in entries) throw YamlException("duplicate key '$key'", line)
                val v: YamlNode = if (i < s.length && s[i] == ':') {
                    i++
                    ws()
                    if (i < s.length && (s[i] == ',' || s[i] == '}')) YamlScalar("", false, line) else value()
                } else {
                    YamlScalar("", false, line)
                }
                entries[key] = v
                keyLines[key] = line
                ws()
                if (i >= s.length) throw YamlException("missing '}'", line)
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return YamlMap(entries, keyLines, line)
                    }
                    else -> throw YamlException("expected ',' or '}'", line)
                }
            }
        }
    }

    companion object {
        fun isSeqItem(content: String): Boolean = content == "-" || content.startsWith("- ")

        /** Index of the ':' that makes this line a mapping entry, or -1. */
        fun findMapColon(content: String): Int {
            if (content.isEmpty()) return -1
            val c0 = content[0]
            if (c0 == '[' || c0 == '{') return -1
            if (c0 == '"' || c0 == '\'') {
                val end = quotedEnd(content, 0)
                if (end < 0) return -1
                var k = end
                while (k < content.length && content[k] == ' ') k++
                return if (k < content.length && content[k] == ':' && (k + 1 == content.length || content[k + 1] == ' ')) k else -1
            }
            for (k in content.indices) {
                if (content[k] == ':' && (k + 1 == content.length || content[k + 1] == ' ')) return k
            }
            return -1
        }

        /** Index just past the closing quote of the quoted string starting at [start], or -1. */
        fun quotedEnd(s: String, start: Int): Int {
            val q = s[start]
            var k = start + 1
            while (k < s.length) {
                val c = s[k]
                if (q == '"' && c == '\\') {
                    k += 2
                    continue
                }
                if (c == q) {
                    if (q == '\'' && k + 1 < s.length && s[k + 1] == '\'') {
                        k += 2
                        continue
                    }
                    return k + 1
                }
                k++
            }
            return -1
        }

        fun readQuoted(s: String, start: Int, line: Int): Pair<String, Int> {
            val q = s[start]
            val sb = StringBuilder()
            var k = start + 1
            while (k < s.length) {
                val c = s[k]
                if (q == '\'') {
                    if (c == '\'') {
                        if (k + 1 < s.length && s[k + 1] == '\'') {
                            sb.append('\'')
                            k += 2
                            continue
                        }
                        return sb.toString() to k + 1
                    }
                    sb.append(c)
                    k++
                } else {
                    when (c) {
                        '"' -> return sb.toString() to k + 1
                        '\\' -> {
                            if (k + 1 >= s.length) throw YamlException("unfinished escape sequence", line)
                            when (val e = s[k + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                '0' -> sb.append('\u0000')
                                '"', '\\', '/', '\'' -> sb.append(e)
                                'u' -> {
                                    if (k + 6 > s.length) throw YamlException("bad \\u escape", line)
                                    val hex = s.substring(k + 2, k + 6)
                                    val code = hex.toIntOrNull(16) ?: throw YamlException("bad \\u escape '\\u$hex'", line)
                                    sb.append(code.toChar())
                                    k += 4
                                }
                                else -> throw YamlException("unknown escape '\\$e'", line)
                            }
                            k += 2
                        }
                        else -> {
                            sb.append(c)
                            k++
                        }
                    }
                }
            }
            throw YamlException("missing closing $q", line)
        }

        /**
         * Removes a trailing `# comment`. A quote only opens a quoted string at the start of a
         * value (after `: `, `- `, `[`, `{` or `,`), so apostrophes inside plain text are safe.
         */
        fun stripComment(s: String): String {
            var k = 0
            var prevSignificant = ' ' // ' ' means "start of value"
            var prevChar = ' '
            while (k < s.length) {
                val c = s[k]
                if ((c == '"' || c == '\'') && prevSignificant in VALUE_STARTERS && (prevChar == ' ' || prevChar in "[{,")) {
                    val end = quotedEnd(s, k)
                    if (end < 0) return s // unterminated; let the parser report it
                    k = end
                    prevSignificant = c
                    prevChar = c
                    continue
                }
                if (c == '#' && (k == 0 || s[k - 1] == ' ')) return s.substring(0, k)
                if (c != ' ') prevSignificant = c
                prevChar = c
                k++
            }
            return s
        }

        private const val VALUE_STARTERS = " :-[{,"
    }
}
