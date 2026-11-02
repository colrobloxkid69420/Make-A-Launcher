package io.github.makealauncher.core

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.runtime.Measurer
import io.github.makealauncher.core.runtime.PageRun
import io.github.makealauncher.core.runtime.SubRun
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        val run = card.run!!
        assertEquals(1000f, run.shift, "page 1 starts one screen to the right")
        assertFalse(run.awake, "asleep while off screen, so it isn't drawn")
        assertEquals(0f, card.drawX, "the page shift is applied by its run, not the layer")
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
        // The paged card doesn't move itself; its page run does. Only the animated layer moves.
        assertEquals(setOf("parallax"), rig.ids(rt.transformChanged))
        val run = rt.node("page1-card")!!.run!!
        assertTrue(run in rt.runsMoved)
        assertEquals(0f, run.shift)
        assertTrue(run.awake)
        assertEquals(bindingRunsBefore, rig.graph.effectRuns, "scrolling must never run bindings")
        assertEquals(measuresBefore, rig.measurer.calls)

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
        // Only things showing seconds that are on or near the screen (now page 3) redraw:
        // the fixed header and page 3's seconds items, never the ~290 others or page 1's.
        val redrawn = rt.contentChanged.map { it.id }.toSet()
        assertTrue(redrawn.containsAll(setOf("header-clock", "item-201", "item-251")), redrawn.toString())
        assertTrue(redrawn.none { it == "item-1" || it == "item-51" }, redrawn.toString())
        assertTrue(redrawn.size <= 7, redrawn.toString())
    }

    @Test
    fun sleepingPagesArentMovedAndWakeWithTheRightPosition() {
        val result = PresetLoader.load(StressPreset.files(count = 300, pages = 3))
        val graph = Graph()
        val rt = SceneRuntime(result.preset!!, graph, DataSources(graph), FakeMeasurer(), density = 3f)
        rt.setViewport(1080f, 2400f)
        rt.update()
        val page3 = (200 until 300).map { "item-$it" }.toSet()
        assertTrue(page3.all { rt.node(it)!!.paused })

        val page3Run = rt.node("item-250")!!.run!!
        assertFalse(page3Run.awake)

        rt.setScroll(0.2f)
        rt.update()
        val moved = rt.transformChanged.map { it.id }.toSet()
        assertTrue(moved.none { it in page3 }, "page 3 is asleep and mustn't be moved")
        assertTrue(page3Run !in rt.runsMoved)
        assertTrue(moved.size <= 200, "only pages 1 and 2 animate, got ${moved.size}")

        rt.setScroll(1.5f)
        rt.update()
        val item = rt.node("item-250")!!
        assertFalse(item.paused)
        assertTrue(page3Run.awake)
        assertTrue(page3Run in rt.runsMoved)
        assertEquals((2 - 1.5f) * 1080f, page3Run.shift)
        assertTrue(item in rt.transformChanged, "its animation transform, skipped while asleep, is pushed")
        assertTrue(item.drawAlpha > 0f)
    }

    @Test
    fun sleepCheckUsesWhatALayerActuallyPaints() {
        val rig = Rig(
            """
            pages: 3
            layers:
              - id: page-2-background
                type: group
                page: 1
                width: 500
                height: 1000
                children:
                  - id: bg
                    type: shape
                    width: 500
                    height: 1000
              - id: page-3-card
                type: group
                page: 2
                width: 100
                height: 100
                children:
                  - id: sticks-out
                    type: shape
                    width: 100
                    height: 100
                    offset: [-400, 0]
            """.trimIndent(),
        )
        val rt = rig.runtime
        rt.update()
        // A full-screen group on the next page touches the screen edge but isn't on it.
        assertTrue(rt.node("page-2-background")!!.paused)
        assertTrue(rt.node("page-3-card")!!.paused)
        // On page 2 this group's own box is still off screen to the right, but its child
        // reaches back 800 px onto the screen, so it must be awake.
        rt.setScroll(1f)
        rt.update()
        val card = rt.node("page-3-card")!!
        assertTrue(card.left + card.pageShift >= 1000f, "the group's own box is off screen")
        assertFalse(card.paused)
    }

    @Test
    fun typicalStressPresetAnimatesOnlySomeItems() {
        val result = PresetLoader.load(StressPreset.files(count = 300, pages = 3, animateEvery = 10))
        assertEquals(emptyList(), result.errors.map { it.toString() })
        val animated = result.preset!!.scene.layers.count { it.animations.isNotEmpty() }
        assertEquals(30, animated)
    }

    private val pagedScene = """
        pages: 3
        layers:
          - id: fixed
            type: text
            text: '{time.format("HH:mm")}'
          - id: seconds
            type: text
            page: 1
            text: '{time.format("ss")}'
            anchor: center
          - id: level
            type: text
            page: 2
            text: '{battery.level}'
            anchor: center
    """.trimIndent()

    @Test
    fun offscreenPagesArePausedAndCatchUpWhenShown() {
        val rig = Rig(pagedScene)
        val rt = rig.runtime
        rt.update()
        assertTrue(rt.node("seconds")!!.paused)
        assertTrue(rt.node("level")!!.paused)
        assertFalse(rig.data.time.secondsNeeded, "no seconds timer while the seconds page is off screen")

        // Data changes while off screen: nothing runs.
        val runs = rig.graph.effectRuns
        rig.data.battery.level.set(33)
        rig.now = rig.now.plusSeconds(5)
        rig.data.time.onSecond()
        rt.update()
        assertEquals(runs, rig.graph.effectRuns)

        // Scroll to the last page: the battery text catches up in the same frame it appears.
        rt.setScroll(2f)
        rt.update()
        assertEquals("33", rt.node("level")!!.text)
        assertTrue(rt.node("seconds")!!.paused)

        // On page 2 the seconds display is shown with the current time.
        rt.setScroll(1f)
        rt.update()
        assertFalse(rt.node("seconds")!!.paused)
        assertEquals("12", rt.node("seconds")!!.text)
        assertTrue(rig.data.time.secondsNeeded)
    }

    @Test
    fun secondsTimerFollowsWhatIsOnScreen() {
        val rig = Rig(pagedScene)
        val rt = rig.runtime
        val changes = ArrayList<Boolean>()
        rig.data.time.onSecondsNeededChanged = { needed ->
            changes += needed
            if (needed) rig.data.time.onSecond()
        }
        rt.update()
        assertEquals(listOf(false), changes)
        rt.setScroll(0.5f)
        rt.update()
        assertEquals(listOf(false, true), changes)
        // Ticking while visible keeps the subscription; it doesn't flap.
        repeat(3) {
            rig.data.time.onSecond()
            rt.update()
        }
        assertEquals(listOf(false, true), changes)
        rt.setScroll(0f)
        rt.update()
        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun pausingCanBeTurnedOff() {
        val rig = Rig(pagedScene)
        val rt = rig.runtime
        rt.pauseOffscreen = false
        rt.update()
        assertFalse(rt.node("seconds")!!.paused)
        assertTrue(rig.data.time.secondsNeeded)
    }

    private val layerScene = """
        pages: 2
        layers:
          - id: card
            type: group
            page: 0
            width: 200
            height: 100
            children:
              - id: card-bg
                type: shape
                width: 200
                height: 100
              - id: card-text
                type: text
                text: Hello
          - id: big
            type: text
            page: 1
            text: '12:00'
            size: 64
            offset: [24, 24]
          - id: small
            type: text
            page: 1
            text: hi
            offset: [24, 120]
          - id: animated-group
            type: group
            page: 1
            children:
              - id: spinner
                type: shape
                animate:
                  - on: scroll
                    rotation: [0, 90]
              - id: spinner-label
                type: text
                text: x
                offset: [24, 0]
          - id: fixed-big
            type: text
            text: Fixed
            size: 64
    """.trimIndent()

    @Test
    fun staticPageContentIsCachedPerRunWhileAwake() {
        val rig = Rig(layerScene)
        val rt = rig.runtime
        rt.update()
        val cardSub = rt.node("card")!!.subRun!!
        assertTrue(cardSub.layered, "page 1's static content is cached while it's on screen")
        val b = cardSub.bounds!!
        // The group's box (400 x 200 px) plus text overhang and padding, in page coordinates.
        assertTrue(b.left < 0f && b.top < 0f && b.right >= 400f && b.bottom >= 200f, b.toString())

        val page2 = rt.node("big")!!.run!!
        assertEquals(listOf("big", "small"), (page2.parts[0] as SubRun).nodes.map { it.id })
        assertSame<Any?>(rt.node("animated-group"), page2.parts[1], "a layer with moving parts is drawn on its own")
        assertFalse((page2.parts[0] as SubRun).layered, "asleep, so no layer memory is used")
        assertNull(rt.node("fixed-big")!!.layerBounds, "doesn't move")

        rt.setScroll(1f)
        rt.update()
        assertTrue((page2.parts[0] as SubRun).layered)
        assertTrue(page2 in rt.runsChanged)
        assertTrue(rt.contentChanged.isEmpty(), "waking a page re-records nothing but its run")

        rt.cacheLayers = false
        rt.update()
        assertFalse((page2.parts[0] as SubRun).layered)
    }

    @Test
    fun expensiveAnimatedLayersGetTheirOwnLayer() {
        val rig = Rig(
            """
            pages: 2
            layers:
              - id: fading-clock
                type: text
                text: '12:00'
                size: 64
                animate:
                  - on: scroll
                    alpha: [1, 0]
              - id: fading-small
                type: text
                text: hi
                animate:
                  - on: scroll
                    alpha: [1, 0]
              - id: sliding-card
                type: group
                page: 1
                width: 100
                height: 100
                animate:
                  - on: scroll
                    translate_x: [200, 0]
                children:
                  - id: card-bg
                    type: shape
                  - id: card-label
                    type: text
                    text: Card
            """.trimIndent(),
        )
        val rt = rig.runtime
        rt.update()
        assertNotNull(rt.node("fading-clock")!!.layerBounds)
        assertNull(rt.node("fading-small")!!.layerBounds, "small text is cheap to draw")
        assertNotNull(rt.node("sliding-card")!!.layerBounds)
        assertNull(rt.node("sliding-card")!!.subRun, "it animates, so it's drawn on its own")

        rt.setScroll(0.5f)
        rt.update()
        assertTrue(rt.contentChanged.isEmpty(), "scrolling never re-records")
    }

    @Test
    fun runsKeepTheSceneDrawingOrder() {
        val rig = Rig(
            """
            pages: 2
            layers:
              - {id: bg, type: shape}
              - {id: a, type: shape, page: 0}
              - id: b
                type: shape
                page: 0
                animate:
                  - on: scroll
                    alpha: [1, 0]
              - {id: c, type: shape, page: 0}
              - {id: overlay, type: shape}
              - {id: d, type: shape, page: 0}
              - {id: e, type: shape, page: 1}
            """.trimIndent(),
        )
        val rt = rig.runtime
        fun describe(x: Any): String = when (x) {
            is RuntimeNode -> x.id
            is SubRun -> x.nodes.joinToString(",", "[", "]") { it.id }
            is PageRun -> "page${x.page}(" + x.parts.joinToString(" ") { describe(it) } + ")"
            else -> "?"
        }
        assertEquals(
            listOf("bg", "page0([a] b [c])", "overlay", "page0([d])", "page1([e])"),
            rt.rootEntries.map { describe(it) },
        )
    }

    @Test
    fun scrollingATypicalPresetMovesPagesNotLayers() {
        val result = PresetLoader.load(StressPreset.files(count = 300, pages = 3, animateEvery = 10))
        val graph = Graph()
        val rt = SceneRuntime(result.preset!!, graph, DataSources(graph), FakeMeasurer(), density = 3f)
        rt.setViewport(1080f, 2400f)
        rt.update()
        assertEquals(3, rt.runs.size)
        var maxMoves = 0
        var p = 0f
        while (p <= 2f) {
            rt.setScroll(p)
            rt.update()
            maxMoves = maxOf(maxMoves, rt.transformChanged.size + rt.runsMoved.size)
            assertTrue(rt.contentChanged.isEmpty())
            p += 1f / 60f
        }
        // Up to 3 runs plus the 1 in 10 items that animate on the awake pages (at most 30).
        assertTrue(maxMoves <= 33, "moves per frame: $maxMoves")
    }

    @Test
    fun aTickRelaysOutOnlyWhatChanged() {
        val result = PresetLoader.load(StressPreset.files(count = 300, pages = 3, animateEvery = 10))
        val graph = Graph()
        var now = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, ZoneOffset.UTC)
        val data = DataSources(graph) { now }
        val rt = SceneRuntime(result.preset!!, graph, data, FakeMeasurer(), density = 3f)
        rt.setViewport(1080f, 2400f)
        rt.update()
        val before = rt.stats.nodesLaidOut
        now = now.plusSeconds(1)
        data.time.onSecond()
        rt.update()
        val laidOut = rt.stats.nodesLaidOut - before
        // The header and the seconds items on page 1, not all 301 layers.
        assertTrue(laidOut in 1..5, "laid out $laidOut nodes")
    }

    @Test
    fun singleDrawNodesAvoidOffscreenAlpha() {
        val rig = Rig(layerScene)
        val rt = rig.runtime
        rt.update()
        assertTrue(rt.node("small")!!.drawsOnce)
        assertTrue(rt.node("card-bg")!!.drawsOnce) // no fill or stroke set: nothing overlaps
        assertFalse(rt.node("card")!!.drawsOnce)
    }
}
