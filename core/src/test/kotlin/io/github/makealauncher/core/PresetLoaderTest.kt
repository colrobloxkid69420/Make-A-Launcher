package io.github.makealauncher.core

import io.github.makealauncher.core.scene.Anchor
import io.github.makealauncher.core.scene.ContentSpec
import io.github.makealauncher.core.scene.GlobalType
import io.github.makealauncher.core.scene.PresetFiles
import io.github.makealauncher.core.scene.PresetLoader
import io.github.makealauncher.core.scene.Severity
import io.github.makealauncher.core.scene.Value
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetLoaderTest {
    private fun files(scene: String, globals: String? = null) = PresetFiles { path ->
        when (path) {
            "scene.yaml" -> scene
            "globals.yaml" -> globals
            else -> null
        }
    }

    @Test
    fun loadsAValidPreset() {
        val result = PresetLoader.load(
            files(
                """
                pages: 2
                background: '#FF101418'
                layers:
                  - id: clock
                    type: text
                    page: 0
                    text: '{time.format("HH:mm")}'
                    size: 72
                    color: '{globals.accent}'
                    anchor: top-center
                    offset: [0, 120]
                    animate:
                      - on: scroll
                        translate_x: [0, -400]
                        alpha: [1, 0]
                  - id: card
                    type: group
                    children:
                      - id: card-bg
                        type: shape
                        radius: 24
                        fill: '#FF202830'
                """.trimIndent(),
                globals = "accent: '#FF7AA2F7'\nshow_seconds: false\nscale: 1.5\n",
            ),
        )
        assertEquals(emptyList(), result.problems.map { it.toString() })
        val preset = assertNotNull(result.preset)
        assertEquals(2, preset.scene.pages)
        assertEquals(listOf(GlobalType.COLOR, GlobalType.SWITCH, GlobalType.NUMBER), preset.globals.map { it.type })
        val clock = preset.scene.layers[0]
        assertEquals(Anchor.TOP_CENTER, clock.anchor)
        assertEquals(0, clock.page)
        val text = assertIs<ContentSpec.Text>(clock.content)
        assertIs<Value.Bound<String>>(text.text)
        assertEquals(Value.Const(72f), text.size)
        assertEquals(1, clock.animations.size)
        assertEquals("card-bg", preset.scene.layers[1].children[0].id)
    }

    @Test
    fun reportsEveryProblemWithItsLine() {
        val result = PresetLoader.load(
            files(
                """
                layers:
                  - id: a
                    type: text
                    colr: '#FFFFFF'
                  - id: a
                    type: shape
                    fill: {globals.accent}
                  - id: b
                    type: text
                    text: '{battery.levl}'
                  - type: txt
                """.trimIndent(),
            ),
        )
        assertNull(result.preset)
        val messages = result.errors.map { "${it.line}: ${it.message}" }
        assertTrue(messages.any { it.startsWith("4:") && "did you mean 'color'" in it }, messages.toString())
        assertTrue(messages.any { it.startsWith("5:") && "duplicate id 'a'" in it }, messages.toString())
        assertTrue(messages.any { it.startsWith("7:") && "must be in quotes" in it }, messages.toString())
        assertTrue(messages.any { it.startsWith("10:") && "did you mean 'level'" in it }, messages.toString())
        assertTrue(messages.any { it.startsWith("11:") && "unknown layer type 'txt'" in it }, messages.toString())
    }

    @Test
    fun missingIdIsAWarningNotAnError() {
        val result = PresetLoader.load(files("layers:\n  - type: shape\n"))
        assertNotNull(result.preset)
        assertEquals(Severity.WARNING, result.problems.single().severity)
        assertEquals("shape-1", result.preset!!.scene.layers[0].id)
    }

    @Test
    fun yamlSyntaxErrorsSurfaceAsProblems() {
        val result = PresetLoader.load(files("layers:\n\t- id: x\n"))
        assertNull(result.preset)
        assertEquals(2, result.errors.single().line)
    }

    @Test
    fun missingSceneFile() {
        val result = PresetLoader.load { null }
        assertNull(result.preset)
        assertTrue("scene.yaml is missing" in result.errors.single().message)
    }
}
