package io.github.makealauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import io.github.makealauncher.core.runtime.SceneRuntime
import java.util.Locale
import kotlin.math.max

/**
 * The Phase 1 benchmark readout: frame times from the system's FrameMetrics, plus what the
 * engine did each second. It refreshes twice a second, so those refreshes show up as the
 * idle frame rate; everything else on screen draws only when it changes.
 */
class StatsOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = 11f * density
    }
    private val bg = Paint().apply { color = 0xB3000000.toInt() }
    private val box = RectF()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val frameNanos = LongArray(240)
    private var frameCount = 0
    private var framesInWindow = 0
    private var refreshNanos = 16_666_667L

    private var runtime: SceneRuntime? = null
    private var scene: SceneView? = null
    private var presetName = ""
    private var problems = emptyList<String>()
    private var lines = listOf("Loading…")

    private var lastSampleTime = 0L
    private var lastBindingRuns = 0L
    private var lastContent = 0L
    private var lastTransforms = 0L
    private var lastWorkNanos = 0L
    private var lastWorkFrames = 0L

    private val metricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        frameNanos[frameCount % frameNanos.size] = total
        frameCount++
        framesInWindow++
    }

    private val tick = object : Runnable {
        override fun run() {
            sample()
            invalidate()
            uiHandler.postDelayed(this, 500)
        }
    }

    fun attach(window: Window, refreshRate: Float) {
        if (refreshRate > 0f) refreshNanos = (1_000_000_000L / refreshRate).toLong()
        window.addOnFrameMetricsAvailableListener(metricsListener, uiHandler)
    }

    fun detach(window: Window) {
        window.removeOnFrameMetricsAvailableListener(metricsListener)
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
        if (visibility == VISIBLE && isAttachedToWindow) uiHandler.post(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) uiHandler.post(tick)
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
        if (lastSampleTime == 0L) {
            lastSampleTime = now
            lastBindingRuns = stats.bindingRuns
            lastContent = stats.contentUpdates
            lastTransforms = stats.transformUpdates
            lastWorkNanos = sv.totalWorkNanos
            lastWorkFrames = sv.workFrames
            framesInWindow = 0
            return
        }
        val seconds = (now - lastSampleTime) / 1e9
        if (seconds < 0.25) return

        val n = minOf(frameCount, frameNanos.size)
        val recent = frameNanos.copyOf(n).sortedArray()
        val p50 = if (n > 0) recent[n / 2] / 1e6 else 0.0
        val p95 = if (n > 0) recent[minOf(n - 1, (n * 95) / 100)] / 1e6 else 0.0
        val slow = if (n > 0) recent.count { it > refreshNanos } * 100 / n else 0
        val fps = framesInWindow / seconds
        val workFrames = sv.workFrames - lastWorkFrames
        val workMs = if (workFrames > 0) (sv.totalWorkNanos - lastWorkNanos) / 1e6 / workFrames else 0.0

        lines = buildList {
            add("$presetName · ${rt.nodes.size - 1} components · page ${fmt(rt.scrollPosition + 1, 1)}/${rt.pages}")
            add("frames/s ${fmt(fps, 0)}   p50 ${fmt(p50, 1)} ms   p95 ${fmt(p95, 1)} ms   slow ${slow}%")
            add("engine ${fmt(workMs, 2)} ms/frame")
            add(
                "per s: ${rate(stats.bindingRuns, lastBindingRuns, seconds)} bindings · " +
                    "${rate(stats.contentUpdates, lastContent, seconds)} redraws · " +
                    "${rate(stats.transformUpdates, lastTransforms, seconds)} moves",
            )
            val errors = rt.runtimeErrors.size + problems.size
            if (errors > 0) add("$errors warning(s); see logcat")
            add("long-press: presets and stats")
        }

        lastSampleTime = now
        lastBindingRuns = stats.bindingRuns
        lastContent = stats.contentUpdates
        lastTransforms = stats.transformUpdates
        lastWorkNanos = sv.totalWorkNanos
        lastWorkFrames = sv.workFrames
        framesInWindow = 0
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
