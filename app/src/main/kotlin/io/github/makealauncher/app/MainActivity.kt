package io.github.makealauncher.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.runtime.SceneRuntime
import io.github.makealauncher.core.scene.PresetFiles
import io.github.makealauncher.core.scene.PresetLoader
import io.github.makealauncher.core.scene.Severity
import io.github.makealauncher.core.scene.StressPreset

/**
 * Phase 1 host: loads a preset, runs it in a [SceneView], and shows the benchmark statsOverlay.
 * Long-press anywhere to switch between the demo preset and the 300-component stress test.
 */
class MainActivity : Activity() {
    private lateinit var sceneView: SceneView
    private lateinit var statsOverlay: StatsOverlay

    private var runtime: SceneRuntime? = null
    private var sources: AndroidDataSources? = null
    private var started = false
    private var currentPreset = PRESET_DEMO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sceneView = SceneView(this).apply { onLongPress = { showMenu() } }
        statsOverlay = StatsOverlay(this).apply {
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val root = FrameLayout(this).apply {
            addView(sceneView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(statsOverlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(root)
        statsOverlay.attach(window, display?.refreshRate ?: 60f)

        currentPreset = savedInstanceState?.getString(KEY_PRESET) ?: intent.getStringExtra(KEY_PRESET) ?: PRESET_DEMO
        load(currentPreset)
    }

    override fun onStart() {
        super.onStart()
        started = true
        sources?.start()
        sceneView.requestFrame()
    }

    override fun onStop() {
        // Sleep hard: no tickers or listeners while the launcher isn't visible.
        sources?.stop()
        started = false
        super.onStop()
    }

    override fun onDestroy() {
        statsOverlay.detach(window)
        runtime?.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PRESET, currentPreset)
    }

    private fun load(which: String) {
        val assetsDir: PresetAssets?
        val files: PresetFiles
        if (which == PRESET_STRESS) {
            assetsDir = null
            files = StressPreset.files(count = 300, pages = 3)
        } else {
            assetsDir = PresetAssets(assets, "presets/demo")
            files = assetsDir
        }

        val result = PresetLoader.load(files)
        for (p in result.problems) Log.w(TAG, p.toString())
        val preset = result.preset
        if (preset == null) {
            statsOverlay.showErrors("Preset failed to load:", result.errors.map { it.toString() })
            return
        }

        sources?.stop()
        runtime?.dispose()

        val graph = Graph()
        val data = DataSources(graph)
        val text = TextLayouts(assetsDir)
        val images = Images(assetsDir)
        val rt = SceneRuntime(preset, graph, data, AndroidMeasurer(text, images), resources.displayMetrics.density)
        graph.onSchedule = { sceneView.requestFrame() }
        runtime = rt
        currentPreset = which

        sceneView.setScene(rt, SceneRenderer(text, images))
        statsOverlay.bind(
            rt,
            sceneView,
            preset.manifest.name,
            result.problems.filter { it.severity == Severity.WARNING }.map { it.toString() },
        )

        val s = AndroidDataSources(this, data)
        sources = s
        if (started) s.start()
    }

    private fun showMenu() {
        val stats = if (statsOverlay.visibility == View.VISIBLE) "Hide stats" else "Show stats"
        AlertDialog.Builder(this)
            .setTitle("Phase 1 prototype")
            .setItems(arrayOf("Demo preset", "Stress test: 300 components", stats)) { _, i ->
                when (i) {
                    0 -> load(PRESET_DEMO)
                    1 -> load(PRESET_STRESS)
                    2 -> statsOverlay.visibility = if (statsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            .show()
    }

    private companion object {
        const val TAG = "MakeALauncher"
        const val KEY_PRESET = "preset"
        const val PRESET_DEMO = "demo"
        const val PRESET_STRESS = "stress"
    }
}
