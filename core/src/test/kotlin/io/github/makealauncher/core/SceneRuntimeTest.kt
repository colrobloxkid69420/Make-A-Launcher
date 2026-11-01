package io.github.makealauncher.core

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.runtime.Measurer
import io.github.makealauncher.core.runtime.RuntimeNode
import io.github.makealauncher.core.runtime.SceneRuntime
import io.github.makealauncher.core.runtime.Size
import io.github.makealauncher.core.scene.PresetFiles
import io.github.makealauncher.core.scene.PresetLoader
import io.github.makealauncher.core.scene.StressPreset
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text is 0.5 × size per character wide and 1.2 × size tall. */
class FakeMeasurer : Measurer {
    var calls = 0
    override fun measureText(node: RuntimeNode, maxWidth: Float?): Size {
        calls++
        val w = node.text.length * node.textSize * 0.5f
        return Size(if (maxWidth != null) minOf(w, maxWidth) else w, node.textSize * 1.2f)
    }

    override fun imageSize(node: RuntimeNode): Size? = null
}

class SceneRuntimeTest {
    private val scene = """
        pages: 3
        layers:
          - id: clock
            type: text
            text: '{time.format("HH:mm")}'
            size: 20
            anchor: top-center
            offset: [0, 10]
          - id: seconds
            type: text
            text: '{time.format("ss")}'
          - id: battery
            type: text
            text: 'Battery {battery.level}%'
            color: '{globals.accent}'
          - id: page1-card
            type: shape
            page: 1
            width: 100
            height: 50
          - id: parallax
            type: shape
            animate:
              - on: scroll
                range: [0, 2]
                translate_x: [0, -100]
                alpha: [1, 0]
          - id: static
            type: text
            text: Hello
    """.trimIndent()

    private class Rig(scene: String) {
        val graph = Graph()
        var now: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, ZoneOffset.UTC)
        val data = DataSources(graph) { now }
        val measurer = FakeMeasurer()
        val runtime: SceneRuntime

        init {
            val result = PresetLoader.load(
                PresetFiles { p ->
                    when (p) {
                        "scene.yaml" -> scene
                        "globals.yaml" -> "accent: '#FF7AA2F7'"
                        else -> null
                    }
                },
            )
            check(result.errors.isEmpty()) { result.problems.joinToString("\n") }
            runtime = SceneRuntime(result.preset!!, graph, data, measurer, density = 2f)
            runtime.setViewport(1000f, 2000f)
        }

        fun ids(list: List<RuntimeNode>) = list.map { it.id }.toSet()
    }

    @Test
    fun firstFrameDrawsEverythingThenGoesIdle() {
        val rig = Rig(scene)
        val rt = rig.runtime
        assertTrue(rt.update())
        assertEquals(rt.nodes.size, rt.contentChanged.size)
        assertEquals("09:05", rt.node("clock")!!.text)
        assertEquals("Battery 100%", rt.node("battery")!!.text)
        assertEquals(0xFF7AA2F7.toInt(), rt.node("battery")!!.color)

        assertFalse(rt.needsUpdate)
        assertFalse(rt.update())
        assertTrue(rt.contentChanged.isEmpty() && rt.transformChanged.isEmpty())
    }

    @Test
    fun layoutUsesAnchorsOffsetsAndDensity() {
        val rt = Rig(scene).runtime
        rt.update()
        val clock = rt.node("clock")!!
        // "09:05" = 5 chars × 40px × 0.5 = 100px wide; centred in 1000px; offset 10dp = 20px.
        assertEquals(100f, clock.width)
        assertEquals(450f, clock.left)
        assertEquals(20f, clock.top)
        val card = rt.node("page1-card")!!
        assertEquals(200f, card.width)
        assertEquals(1000f, card.drawX, "page 1 starts one screen to the right")
    }

    @Test
    fun scrollingTouchesOnlyTransformsAndRunsNoBindings() {
        val rig = Rig(scene)
        val rt = rig.runtime
        rt.update()
        val bindingRunsBefore = rig.graph.effectRuns
        val measuresBefore = rig.measurer.calls

        rt.setScroll(1f)
        assertTrue(rt.update())
        assertEquals(emptyList(), rt.contentChanged.map { it.id }, "scrolling must never re-record drawings")
        assertEquals(setOf("page1-card", "parallax"), rig.ids(rt.transformChanged))
        assertEquals(bindingRunsBefore, rig.graph.effectRuns, "scrolling must never run bindings")
        assertEquals(measuresBefore, rig.measurer.calls)

        val card = rt.node("page1-card")!!
        assertEquals(0f, card.drawX)
        val parallax = rt.node("parallax")!!
        assertEquals(-100f, parallax.animTx) // halfway through [0, -100] dp at density 2
        assertEquals(0.5f, parallax.drawAlpha)
    }

    @Test
    fun aSecondsTickReRecordsOnlyTheSecondsText() {
        val rig = Rig(scene)
        val rt = rig.runtime
        rt.update()
        rig.now = rig.now.plusSeconds(1)
        rig.data.time.onSecond()
        assertTrue(rt.needsUpdate)
        rt.update()
        assertEquals(setOf("seconds"), rig.ids(rt.contentChanged))
        assertEquals("08", rt.node("seconds")!!.text)

        rig.data.time.onMinute()
        rt.update()
        // The minute tick re-evaluates the HH:mm clock, but its text ("09:05") didn't change,
        // so nothing is re-recorded.
        assertTrue(rt.contentChanged.isEmpty())
    }

    @Test
    fun batteryChangeUpdatesOnlyItsNode() {
        val rig = Rig(scene)
        val rt = rig.runtime
        rt.update()
        rig.data.battery.level.set(57)
        rt.update()
        assertEquals(setOf("battery"), rig.ids(rt.contentChanged))
        assertEquals("Battery 57%", rt.node("battery")!!.text)
        // Its text width changed, so it was re-measured and moved; nothing else was.
        assertEquals(setOf("battery"), rig.ids(rt.transformChanged))
    }

    @Test
    fun changingAGlobalUpdatesItsReaders() {
        val rig = Rig(scene)
        val rt = rig.runtime
        rt.update()
        rt.setGlobal("accent", 0xFFFF0000.toInt())
        rt.update()
        assertEquals(setOf("battery"), rig.ids(rt.contentChanged))
        assertEquals(0xFFFF0000.toInt(), rt.node("battery")!!.color)
    }

    @Test
    fun stressPresetLoadsAndScrollsWithoutRebinding() {
        val result = PresetLoader.load(StressPreset.files(count = 300, pages = 3))
        assertEquals(emptyList(), result.errors.map { it.toString() })
        val graph = Graph()
        var now = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, ZoneOffset.UTC)
        val data = DataSources(graph) { now }
        val rt = SceneRuntime(result.preset!!, graph, data, FakeMeasurer(), density = 3f)
        rt.setViewport(1080f, 2400f)
        rt.update()
        assertEquals(302, rt.nodes.size) // root + header + 300 items

        val runs = graph.effectRuns
        var frames = 0
        var p = 0f
        while (p <= 2f) {
            rt.setScroll(p)
            rt.update()
            assertTrue(rt.contentChanged.isEmpty())
            frames++
            p += 1f / 60f
        }
        assertEquals(runs, graph.effectRuns)
        assertTrue(frames > 100)

        now = now.plusSeconds(1)
        data.time.onSecond()
        rt.update()
        // header-clock plus the few items showing seconds; not the other ~290.
        assertEquals(7, rt.contentChanged.size, "seconds tick re-recorded ${rt.contentChanged.map { it.id }}")
    }
}
