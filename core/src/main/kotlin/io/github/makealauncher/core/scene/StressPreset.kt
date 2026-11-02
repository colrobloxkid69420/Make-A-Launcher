package io.github.makealauncher.core.scene

/**
 * Generates the Phase 1 benchmark preset: [count] components spread over [pages] pages, a few
 * bound to a seconds clock. Every [animateEvery]th item also has its own scroll animation:
 * 1 animates everything (the worst case), 10 is closer to a real preset, where most things
 * just sit on their page.
 *
 * It's written out as YAML and loaded like any user preset, so the benchmark also exercises
 * the parser and loader. Build the same layout in KLWP to compare.
 */
object StressPreset {
    fun files(count: Int = 300, pages: Int = 3, animateEvery: Int = 1): PresetFiles {
        val scene = sceneYaml(count, pages, animateEvery)
        val label = if (animateEvery <= 1) "all animated" else "1 in $animateEvery animated"
        return PresetFiles { path ->
            when (path) {
                "scene.yaml" -> scene
                "preset.yaml" -> "name: Stress test ($count components, $label)\nschema: 1\n"
                "globals.yaml" -> "accent: '#FF7AA2F7'\ncard: '#FF1F2335'\n"
                else -> null
            }
        }
    }

    fun sceneYaml(count: Int, pages: Int, animateEvery: Int = 1): String {
        val sb = StringBuilder()
        sb.append("pages: ").append(pages).append('\n')
        sb.append("background: '#FF16161E'\n")
        sb.append("layers:\n")
        // Header fixed on screen, ticking every second: the only thing that should re-record each second.
        sb.append(
            """
            |  - id: header-clock
            |    type: text
            |    text: '{time.format("HH:mm:ss")}'
            |    size: 40
            |    bold: true
            |    color: '{globals.accent}'
            |    anchor: top-center
            |    offset: [0, 48]
            |
            """.trimMargin(),
        )
        val perPage = (count + pages - 1) / pages
        val columns = 6
        var made = 0
        for (p in 0 until pages) {
            for (k in 0 until perPage) {
                if (made >= count) break
                val col = k % columns
                val row = k / columns
                val x = 12 + col * 64
                val y = 120 + row * 48
                val id = "item-$made"
                val depth = 1 + (made % 5)
                val animated = made % animateEvery.coerceAtLeast(1) == 0
                if (made % 2 == 0) {
                    sb.append(
                        """
                        |  - id: $id
                        |    type: shape
                        |    page: $p
                        |    radius: 12
                        |    fill: '{globals.card}'
                        |    stroke: '#33FFFFFF'
                        |    stroke_width: 1
                        |    width: 56
                        |    height: 40
                        |    offset: [$x, $y]
                        |
                        """.trimMargin(),
                    )
                    if (animated) sb.append(
                        """
                        |    animate:
                        |      - on: scroll
                        |        translate_y: [0, ${depth * 8}, 0]
                        |        rotation: [0, ${depth * 2}, 0]
                        |
                        """.trimMargin(),
                    )
                } else {
                    val text = if (made % 25 == 1) "'{time.format(\"ss\")}'" else "'#$made'"
                    sb.append(
                        """
                        |  - id: $id
                        |    type: text
                        |    page: $p
                        |    text: $text
                        |    size: 13
                        |    color: '#CCFFFFFF'
                        |    offset: [${x + 8}, ${y + 10}]
                        |
                        """.trimMargin(),
                    )
                    if (animated) sb.append(
                        """
                        |    animate:
                        |      - on: scroll
                        |        alpha: [1, 0.6, 1]
                        |        scale: [1, ${1 + depth * 0.05}, 1]
                        |
                        """.trimMargin(),
                    )
                }
                made++
            }
        }
        return sb.toString()
    }
}
