package io.github.makealauncher.core

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.expr.DataScope
import io.github.makealauncher.core.expr.ExprException
import io.github.makealauncher.core.expr.Template
import io.github.makealauncher.core.reactive.Graph
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateTest {
    private val schema = DataScope.schema(setOf("accent"))
    private val fixed = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, ZoneOffset.UTC)

    @Test
    fun literalTextAndEscapes() {
        val t = Template.parse("Hello {{world}}", schema)
        assertTrue(t.isConstant)
        assertEquals("Hello {world}", t.evaluate { _, _, _ -> null })
        assertFalse(Template.hasCode("a {{b}}"))
        assertTrue(Template.hasCode("a {battery.level}"))
    }

    @Test
    fun interpolationAndTypedSingleExpression() {
        val g = Graph()
        val data = DataSources(g) { fixed }
        data.battery.level.set(42)
        val scope = DataScope(data, mapOf("accent" to g.source<Any?>(0xFF112233.toInt())))
        assertEquals("Battery 42%", Template.parse("Battery {battery.level}%", schema).evaluate(scope))
        assertEquals(42, Template.parse("{battery.level}", schema).evaluate(scope))
        assertEquals(0xFF112233.toInt(), Template.parse("{globals.accent}", schema).evaluate(scope))
        assertEquals("09:05", Template.parse("{time.format(\"HH:mm\")}", schema).evaluate(scope))
        assertEquals("09:05:07", Template.parse("{time.format('HH:mm:ss')}", schema).evaluate(scope))
    }

    @Test
    fun timeFormatPicksTheNarrowestTick() {
        val g = Graph()
        val data = DataSources(g) { fixed }
        val scope = DataScope(data, emptyMap())
        val hm = Template.parse("{time.format(\"HH:mm\")}", schema)
        val hms = Template.parse("{time.format(\"HH:mm:ss\")}", schema)
        var hmRuns = 0
        var hmsRuns = 0
        g.effect { hm.evaluate(scope); hmRuns++ }
        g.effect { hms.evaluate(scope); hmsRuns++ }

        repeat(30) { data.time.onSecond() }
        g.flush()
        assertEquals(1, hmRuns, "an HH:mm clock must not wake up for seconds")
        assertEquals(2, hmsRuns)

        data.time.onMinute()
        g.flush()
        assertEquals(2, hmRuns)
        assertTrue(data.time.secondsNeeded)
    }

    @Test
    fun loadTimeErrorsAreHelpful() {
        fun msg(src: String) = assertFailsWith<ExprException> { Template.parse(src, schema) }.message!!
        assertTrue("did you mean 'level'" in msg("{battery.levl}"))
        assertTrue("unknown data source 'weather'" in msg("{weather.temp}"))
        assertTrue("invalid time pattern" in msg("{time.format(\"HH:mm qqqqqq\")}"))
        assertTrue("drop the parentheses" in msg("{battery.level()}"))
        assertTrue("missing '}'" in msg("{battery.level"))
        assertTrue("unknown name" in msg("{level}"))
    }

    @Test
    fun secondsDetectionIgnoresQuotedText() {
        assertFalse(DataScope.needsSeconds("HH:mm"))
        assertTrue(DataScope.needsSeconds("mm:ss"))
        assertFalse(DataScope.needsSeconds("HH 'secs'"))
    }
}
