package io.github.makealauncher.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import io.github.makealauncher.core.energy.EnergyLog
import java.util.Locale

/**
 * Repeatable battery tests. Each one keeps the screen on, hides the stats overlay (its own
 * redraws would skew the result), samples every few seconds and then shows a summary that can
 * be shared.
 *
 * - Idle: leave the home screen untouched, like glancing at the phone.
 * - Scroll: pages scroll back and forth automatically, the worst case for animation work.
 *
 * Device current covers the whole phone, mostly the screen. Compare presets by running the
 * same test at the same brightness, with the blank preset as the baseline.
 */
class PowerTests(
    private val activity: Activity,
    private val monitor: EnergyMonitor,
    private val frames: FrameStats,
    private val scene: SceneView,
    private val overlay: View,
    private val presetName: () -> String,
) {
    enum class Kind(val label: String, val minutes: Int) {
        IDLE("Idle test", 10),
        SCROLL("Scroll test", 2),
    }

    private class Running(
        val kind: Kind,
        val startMs: Long,
        val preset: String,
        val overlayVisibility: Int,
        val framesAtStart: Long,
        val slowAtStart: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var running: Running? = null
    private var interruptedMessage: String? = null
    private val finish = Runnable { finish() }

    val isRunning: Boolean get() = running != null

    fun start(kind: Kind) {
        if (running != null) return
        val first = monitor.sample()
        running = Running(kind, first.timeMs, presetName(), overlay.visibility, frames.totalFrames, frames.slowFrames)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        overlay.visibility = View.GONE
        monitor.intervalMs = TEST_INTERVAL_MS
        scene.autoScroll = kind == Kind.SCROLL
        handler.postDelayed(finish, kind.minutes * 60_000L)
        val hint = if (kind == Kind.IDLE) "Don't touch the phone." else "Pages will scroll by themselves."
        Toast.makeText(activity, "${kind.label} running for ${kind.minutes} min. $hint", Toast.LENGTH_LONG).show()
    }

    /** Called when the app leaves the screen; a test can't continue in the background. */
    fun interrupt() {
        val r = running ?: return
        cleanUp(r)
        interruptedMessage = "${r.kind.label} stopped because the app left the screen. Start it again from the menu."
    }

    /** Shows the interruption notice, if any, once the app is back on screen. */
    fun showPendingNotice() {
        val msg = interruptedMessage ?: return
        interruptedMessage = null
        AlertDialog.Builder(activity).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun finish() {
        val r = running ?: return
        monitor.sample()
        val testFrames = frames.totalFrames - r.framesAtStart
        val slow = frames.slowFrames - r.slowAtStart
        val p95 = frames.recentPercentileMs(95)
        cleanUp(r)

        val window = monitor.log.since(r.startMs)
        val summary = EnergyLog.summarize(window, visible = true)
        val lines = buildList {
            add("${r.kind.label} · ${r.preset}")
            if (summary == null) {
                add("Not enough samples to summarise.")
            } else {
                addAll(summary.describe())
                if (testFrames > 0) {
                    add("Slow frames: ${slow * 100 / testFrames}% · recent p95 ${String.format(Locale.US, "%.1f", p95)} ms")
                }
            }
            if (window.any { it.charging }) add("Note: the phone was charging, so drain figures are incomplete.")
        }
        val text = lines.joinToString("\n")
        AlertDialog.Builder(activity)
            .setTitle("Battery test result")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ ->
                share(activity, "Make-A-Launcher ${r.kind.label}", text + "\n\n" + csv(window))
            }
            .show()
    }

    private fun cleanUp(r: Running) {
        handler.removeCallbacks(finish)
        running = null
        scene.autoScroll = false
        monitor.intervalMs = EnergyMonitor.DEFAULT_INTERVAL_MS
        overlay.visibility = r.overlayVisibility
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun csv(samples: List<io.github.makealauncher.core.energy.EnergySample>): String {
        val log = EnergyLog()
        log.addAll(samples)
        return log.toCsv()
    }

    companion object {
        const val TEST_INTERVAL_MS = 5_000L

        fun share(activity: Activity, subject: String, text: String) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text)
            activity.startActivity(Intent.createChooser(send, "Share"))
        }
    }
}
