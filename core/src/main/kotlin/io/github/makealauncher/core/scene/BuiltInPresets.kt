package io.github.makealauncher.core.scene

/** Presets generated in code rather than shipped as files. */
object BuiltInPresets {
    /**
     * A black screen with nothing on it. Measuring battery with this preset gives the baseline
     * (screen, system, idle app); any other preset's extra cost is the difference from it.
     */
    fun blank(pages: Int = 3): PresetFiles = PresetFiles { path ->
        when (path) {
            "scene.yaml" -> "pages: $pages\nbackground: '#FF000000'\nlayers: []\n"
            "preset.yaml" -> "name: Blank (baseline)\nschema: 1\n"
            else -> null
        }
    }
}
