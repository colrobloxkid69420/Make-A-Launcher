package io.github.makealauncher.core

import io.github.makealauncher.core.yaml.Yaml
import io.github.makealauncher.core.yaml.YamlException
import io.github.makealauncher.core.yaml.YamlList
import io.github.makealauncher.core.yaml.YamlMap
import io.github.makealauncher.core.yaml.YamlNode
import io.github.makealauncher.core.yaml.YamlScalar
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YamlTest {
    private fun map(n: YamlNode) = n as YamlMap
    private fun list(n: YamlNode?) = n as YamlList
    private fun text(n: YamlNode?) = (n as YamlScalar).text

    @Test
    fun nestedMappingsAndLists() {
        val doc = map(
            Yaml.parse(
                """
                pages: 3
                layers:
                  - id: clock
                    type: text
                    offset: [0, 120]
                  - id: card
                    animate:
                      - on: scroll
                        alpha: [1, 0]
                """.trimIndent(),
            ),
        )
        assertEquals("3", text(doc["pages"]))
        val layers = list(doc["layers"])
        assertEquals(2, layers.items.size)
        val clock = map(layers.items[0])
        assertEquals("clock", text(clock["id"]))
        assertEquals(listOf("0", "120"), list(clock["offset"]).items.map { text(it) })
        val anim = map(list(map(layers.items[1])["animate"]).items[0])
        assertEquals("scroll", text(anim["on"]))
        assertEquals(6, layers.items[1].line)
    }

    @Test
    fun compactListUnderKey() {
        val doc = map(Yaml.parse("items:\n- a\n- b\nafter: 1"))
        assertEquals(listOf("a", "b"), list(doc["items"]).items.map { text(it) })
        assertEquals("1", text(doc["after"]))
    }

    @Test
    fun quotesEscapesAndComments() {
        val doc = map(
            Yaml.parse(
                """
                a: 'it''s # not a comment'   # but this is
                b: "tab\there \"q\" é"
                c: it's plain # comment
                d: '{time.format("HH:mm")}'
                e: url://x:y
                """.trimIndent(),
            ),
        )
        assertEquals("it's # not a comment", text(doc["a"]))
        assertEquals("tab\there \"q\" é", text(doc["b"]))
        assertEquals("it's plain", text(doc["c"]))
        assertEquals("{time.format(\"HH:mm\")}", text(doc["d"]))
        assertEquals("url://x:y", text(doc["e"]))
        assertTrue((doc["a"] as YamlScalar).quoted)
    }

    @Test
    fun flowMappingAndNulls() {
        val doc = map(Yaml.parse("accent: {type: color, value: '#FF0000'}\nempty:\ntilde: ~"))
        val accent = map(doc["accent"]!!)
        assertEquals("color", text(accent["type"]))
        assertEquals("#FF0000", text(accent["value"]))
        assertTrue((doc["empty"] as YamlScalar).isNull)
        assertTrue((doc["tilde"] as YamlScalar).isNull)
    }

    @Test
    fun blockScalars() {
        val doc = map(
            Yaml.parse(
                "lit: |\n  line one\n  # kept\n\n  line three\nfold: >-\n  a\n  b\nnext: x",
            ),
        )
        assertEquals("line one\n# kept\n\nline three\n", text(doc["lit"]))
        assertEquals("a b", text(doc["fold"]))
        assertEquals("x", text(doc["next"]))
    }

    @Test
    fun errorsCarryLineNumbers() {
        assertEquals(2, assertFailsWith<YamlException> { Yaml.parse("a: 1\n\tb: 2") }.line)
        assertEquals(3, assertFailsWith<YamlException> { Yaml.parse("a: 1\nb: 2\na: 3") }.line)
        assertEquals(2, assertFailsWith<YamlException> { Yaml.parse("a: 1\n   b: 2") }.line)
        assertEquals(1, assertFailsWith<YamlException> { Yaml.parse("a: &anchor 1") }.line)
        assertEquals(2, assertFailsWith<YamlException> { Yaml.parse("a: some text\n  continued") }.line)
        assertEquals(1, assertFailsWith<YamlException> { Yaml.parse("a: [1, 2") }.line)
    }

    @Test
    fun emptyDocumentIsEmptyMap() {
        assertEquals(0, map(Yaml.parse("# nothing\n\n")).entries.size)
    }
}
