package io.github.makealauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import io.github.makealauncher.core.runtime.SceneRuntime
import java.util.Locale
import kotlin.math.max

/**
 * The Phase 1 benchmark readout: frame times, what the engine did each second, and a live
 * power line (the app's CPU use and the phone's current draw).
 *
 * It refreshes twice a second, which shows up as about 2 frames/s while idle and costs a
 * little battery itself; the battery tests hide it while they run.
 */
class StatsOverlay(
    context: Context,
    private val frames: FrameStats,
    private val monitor: EnergyMonitor,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = 11f * density
    }
    private val bg = Paint().apply { color = 0xB3000000.toInt() }
    private val box = RectF()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var runtime: SceneRuntime? = null
    private var scene: SceneView? = null
    private var presetName = ""
    private var problems = emptyList<String>()
    private var lines = listOf("Loading…")

    private var lastSampleTime = 0L
    private var lastFrames = 0L
    private var lastBindingRuns = 0L
    private var lastContent = 0L
    private var lastTransforms = 0L
    private var lastWorkNanos = 0L
    private var lastWorkFrames = 0L
    private var lastCpuMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            sample()
            invalidate()
            uiHandler.postDelayed(this, 500)
        }
    }

    fun bind(runtime: SceneRuntime, scene: SceneView, presetName: String, warnings: List<String>) {
        this.runtime = runtime
        this.scene = scene
        this.presetName = presetName
        this.problems = warnings
        lastSampleTime = 0L
    }

    fun showErrors(title: String, errors: List<String>) {
        runtime = null
        lines = listOf(title) + errors.take(20)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        uiHandler.removeCallbacks(tick)
        lastSampleTime = 0L
        if (isShown && isAttachedToWindow) uiHandler.post(tick)
    }

    /** The window hides when the app leaves the screen; stop ticking so nothing runs in the background. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        uiHandler.removeCallbacks(tick)
        lastSampleTime = 0L
        if (visibility == VISIBLE && isShown) uiHandler.post(tick)
    }

    override fun onDetachedFromWindow() {
        uiHandler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun sample() {
        val rt = runtime ?: return
        val sv = scene ?: return
        val now = System.nanoTime()
        val stats = rt.stats
        val cpuMs = Process.getElapsedCpuTime()
        if (lastSampleTime == 0L) {
            remember(now, rt, sv, cpuMs)
            return
        }
        val seconds = (now - lastSampleTime) / 1e9
        if (seconds < 0.25) return

        val fps = (frames.totalFrames - lastFrames) / seconds
        val workFrames = sv.workFrames - lastWorkFrames
        val workMs = if (workFrames > 0) (sv.totalWorkNanos - lastWorkNanos) / 1e6 / workFrames else 0.0
        val cpuPercent = (cpuMs - lastCpuMs) / 10.0 / seconds
        val current = monitor.currentMicroAmps()

        lines = buildList {
            add("$presetName · ${rt.nodes.size - 1} components · page ${fmt(rt.scrollPosition + 1, 1)}/${rt.pages}")
            add(
                "frames/s ${fmt(fps, 0)}   p50 ${fmt(frames.recentPercentileMs(50), 1)} ms   " +
                    "p95 ${fmt(frames.recentPercentileMs(95), 1)} ms   slow ${frames.recentSlowPercent()}%",
            )
            add("engine ${fmt(workMs, 2)} ms/frame")
            add(
                "per s: ${rate(stats.bindingRuns, lastBindingRuns, seconds)} bindings · " +
                    "${rate(stats.contentUpdates, lastContent, seconds)} redraws · " +
                    "${rate(stats.transformUpdates, lastTransforms, seconds)} moves",
            )
            add("power: app CPU ${fmt(cpuPercent, 1)}% · phone ${current?.let { "${it / 1000} mA" } ?: "n/a"}")
            monitor.lastBackground()?.let { bg ->
                add("last hidden: " + if (bg.clean) "no background work" else "${bg.engineUpdates} updates, ${bg.wakeups} wakeups")
            }
            val errors = rt.runtimeErrors.size + problems.size
            if (errors > 0) add("$errors warning(s); see logcat")
            add("long-press: presets, battery tests")
        }
        remember(now, rt, sv, cpuMs)
    }

    private fun remember(now: Long, rt: SceneRuntime, sv: SceneView, cpuMs: Long) {
        lastSampleTime = now
        lastFrames = frames.totalFrames
        lastBindingRuns = rt.stats.bindingRuns
        lastContent = rt.stats.contentUpdates
        lastTransforms = rt.stats.transformUpdates
        lastWorkNanos = sv.totalWorkNanos
        lastWorkFrames = sv.workFrames
        lastCpuMs = cpuMs
    }

    private fun rate(now: Long, before: Long, seconds: Double) = fmt((now - before) / seconds, 0)

    private fun fmt(v: Number, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v.toDouble())

    override fun onDraw(canvas: Canvas) {
        val lineHeight = paint.fontSpacing
        val pad = 8f * density
        var maxWidth = 0f
        for (l in lines) maxWidth = max(maxWidth, paint.measureText(l))
        val left = paddingLeft + pad
        val top = paddingTop + pad
        box.set(left, top, left + maxWidth + pad * 2, top + lineHeight * lines.size + pad * 2)
        canvas.drawRoundRect(box, pad, pad, bg)
        var y = top + pad - paint.ascent()
        for (l in lines) {
            canvas.drawText(l, left + pad, y, paint)
            y += lineHeight
        }
    }
}
