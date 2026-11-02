package io.github.makealauncher.core

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.runtime.SceneRuntime
import io.github.makealauncher.core.scene.PresetFiles
import io.github.makealauncher.core.scene.PresetLoader
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The demo preset ships in the APK, so it must always load cleanly. */
class DemoPresetTest {
    private val dir = listOf("../app/src/main/assets/presets/demo", "app/src/main/assets/presets/demo")
        .map(::File).first { it.isDirectory }

    @Test
    fun demoLoadsWithoutProblems() {
        val result = PresetLoader.load(PresetFiles { File(dir, it).takeIf(File::exists)?.readText() })
        assertEquals(emptyList(), result.problems.map { it.toString() })
        assertEquals("Demo", result.preset!!.manifest.name)
    }

    @Test
    fun demoRunsAndBindsBatteryToTheBarWidth() {
        val result = PresetLoader.load(PresetFiles { File(dir, it).takeIf(File::exists)?.readText() })
        val graph = Graph()
        val data = DataSources(graph) { ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, ZoneOffset.UTC) }
        val rt = SceneRuntime(result.preset!!, graph, data, FakeMeasurer(), density = 2f)
        rt.setViewport(1080f, 2400f)
        rt.update()
        assertFalse(data.time.secondsNeeded, "the seconds display is on page 2, which is off screen")
        assertEquals(200f, rt.node("battery-bar")!!.width)
        assertTrue(rt.node("battery-card")!!.subRun!!.layered, "page 1's static content is one cached layer")
        assertNotNull(rt.node("clock")!!.layerBounds, "the big clock fades and moves as one cached layer")

        data.battery.level.set(40)
        data.battery.charging.set(true)
        rt.update()
        assertEquals(80f, rt.node("battery-bar")!!.width)
        assertEquals(1f, rt.node("battery-charging")!!.drawAlpha)
        assertTrue(rt.runtimeErrors.isEmpty(), rt.runtimeErrors.toString())

        rt.setScroll(1f)
        rt.update()
        assertTrue(data.time.secondsNeeded, "page 2 is on screen")
    }
}
