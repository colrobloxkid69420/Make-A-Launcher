package io.github.makealauncher.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.energy.EnergyLog
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.runtime.SceneRuntime
import io.github.makealauncher.core.scene.BuiltInPresets
import io.github.makealauncher.core.scene.PresetFiles
import io.github.makealauncher.core.scene.PresetLoader
import io.github.makealauncher.core.scene.Severity
import io.github.makealauncher.core.scene.StressPreset

/**
 * Phase 1 host: loads a preset, runs it in a [SceneView], shows the benchmark overlay and
 * tracks energy use. Long-press anywhere for presets, battery tests and the battery log.
 */
class MainActivity : Activity() {
    private lateinit var sceneView: SceneView
    private lateinit var statsOverlay: StatsOverlay
    private lateinit var monitor: EnergyMonitor
    private lateinit var tests: PowerTests
    private val frameStats = FrameStats()

    // Engine optimizations; switchable from the menu so their effect can be measured.
    private var cacheLayers = true
    private var pauseOffscreen = true

    private var runtime: SceneRuntime? = null
    private var sources: AndroidDataSources? = null
    private var started = false
    private var currentPreset = PRESET_DEMO
    private var currentPresetName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitor = EnergyMonitor(this, frameStats, ThreadCpu())
        sceneView = SceneView(this).apply { onLongPress = { showMenu() } }
        statsOverlay = StatsOverlay(this, frameStats, monitor).apply {
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
        frameStats.attach(window, Handler(Looper.getMainLooper()))
        tests = PowerTests(this, monitor, frameStats, sceneView, statsOverlay, { currentPresetName }, ::settingsSummary)

        currentPreset = savedInstanceState?.getString(KEY_PRESET) ?: intent.getStringExtra(KEY_PRESET) ?: PRESET_DEMO
        load(currentPreset)
    }

    override fun onStart() {
        super.onStart()
        started = true
        sources?.start()
        sceneView.active = true
        monitor.onVisible()
        tests.showPendingNotice()
    }

    override fun onStop() {
        // Sleep hard: no frames, tickers or listeners while the launcher isn't visible.
        tests.interrupt()
        sceneView.active = false
        sources?.stop()
        started = false
        monitor.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        frameStats.detach(window)
        runtime?.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PRESET, currentPreset)
    }

    private fun load(which: String) {
        var assetsDir: PresetAssets? = null
        val files: PresetFiles = when (which) {
            PRESET_STRESS -> StressPreset.files(count = 300, pages = 3)
            PRESET_BLANK -> BuiltInPresets.blank()
            else -> PresetAssets(assets, "presets/demo").also { assetsDir = it }
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
        rt.cacheLayers = cacheLayers
        rt.pauseOffscreen = pauseOffscreen
        graph.onSchedule = { sceneView.requestFrame() }
        runtime = rt
        currentPreset = which
        currentPresetName = preset.manifest.name

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
        if (tests.isRunning) return
        val statsLabel = if (statsOverlay.visibility == View.VISIBLE) "Hide stats" else "Show stats"
        val items = arrayOf(
            "Demo preset",
            "Stress test: 300 components",
            "Blank preset (battery baseline)",
            "Battery test: idle, ${PowerTests.Kind.IDLE.minutes} min",
            "Battery test: scrolling, ${PowerTests.Kind.SCROLL.minutes} min",
            "Battery log",
            "Engine settings…",
            statsLabel,
        )
        AlertDialog.Builder(this)
            .setTitle("Phase 1 prototype")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> load(PRESET_DEMO)
                    1 -> load(PRESET_STRESS)
                    2 -> load(PRESET_BLANK)
                    3 -> tests.start(PowerTests.Kind.IDLE)
                    4 -> tests.start(PowerTests.Kind.SCROLL)
                    5 -> showBatteryLog()
                    6 -> showSettings()
                    7 -> statsOverlay.visibility = if (statsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            .show()
    }

    private fun settingsSummary(): String =
        "layer caching ${if (cacheLayers) "on" else "off"}, off-screen pausing ${if (pauseOffscreen) "on" else "off"}"

    /** Turns engine optimizations on or off, so a battery test can measure what each one saves. */
    private fun showSettings() {
        val checked = booleanArrayOf(cacheLayers, pauseOffscreen)
        AlertDialog.Builder(this)
            .setTitle("Engine settings")
            .setMultiChoiceItems(
                arrayOf("Cache moving layers on the GPU", "Pause pages that are off screen"),
                checked,
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Apply") { _, _ ->
                cacheLayers = checked[0]
                pauseOffscreen = checked[1]
                runtime?.let {
                    it.cacheLayers = cacheLayers
                    it.pauseOffscreen = pauseOffscreen
                    sceneView.requestFrame()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Totals from the whole log: time on screen, time hidden, and whether anything ran hidden. */
    private fun showBatteryLog() {
        monitor.sample()
        val samples = monitor.log.samples
        val lines = buildList {
            add("${samples.size} samples since the log was last cleared.")
            add("")
            add("ON SCREEN")
            EnergyLog.summarize(samples, visible = true)?.let { addAll(it.describe()) } ?: add("No data yet.")
            add("")
            add("HIDDEN")
            add(EnergyLog.background(samples).describe())
            EnergyLog.summarize(samples, visible = false)?.batteryPercentPerHour?.let {
                add("Battery while hidden: ${String.format(java.util.Locale.US, "%.1f", it)}%/hour (whole phone)")
            }
        }
        val text = lines.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Battery log")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ ->
                PowerTests.share(this, "Make-A-Launcher battery log", text + "\n\n" + monitor.log.toCsv(last = SHARE_SAMPLES))
            }
            .setNegativeButton("Clear") { _, _ -> monitor.clear() }
            .show()
    }

    private companion object {
        const val TAG = "MakeALauncher"
        const val KEY_PRESET = "preset"
        const val PRESET_DEMO = "demo"
        const val PRESET_STRESS = "stress"
        const val PRESET_BLANK = "blank"
        const val SHARE_SAMPLES = 2000
    }
}
