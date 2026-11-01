package io.github.makealauncher.app

import android.os.Handler
import android.view.FrameMetrics
import android.view.Window

/**
 * App-wide counters that energy samples read. Everything here runs on the main thread.
 * Each count only goes up, so a sample can compare against the previous one.
 */
object EnergyCounters {
    /** Engine frames that had work to do. */
    var engineUpdates = 0L
    var bindingRuns = 0L
    var redraws = 0L
    var moves = 0L

    /** Timer ticks and broadcasts the app handled. */
    var wakeups = 0L
}

/** Collects real frame times from Android's FrameMetrics for the overlay and the tests. */
class FrameStats {
    private val recent = LongArray(240)

    /** Frames the window rendered since the app started. */
    var totalFrames = 0L
        private set

    /** Frames that took longer than one display refresh. */
    var slowFrames = 0L
        private set

    var refreshNanos = 16_666_667L
        private set

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        recent[(totalFrames % recent.size).toInt()] = total
        totalFrames++
        if (total > refreshNanos) slowFrames++
    }

    fun attach(window: Window, handler: Handler, refreshRate: Float) {
        if (refreshRate > 0f) refreshNanos = (1_000_000_000L / refreshRate).toLong()
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun detach(window: Window) {
        window.removeOnFrameMetricsAvailableListener(listener)
    }

    /** Percentile [p] (0..100) of the last few hundred frame times, in ms. */
    fun recentPercentileMs(p: Int): Double {
        val n = minOf(totalFrames, recent.size.toLong()).toInt()
        if (n == 0) return 0.0
        val sorted = recent.copyOf(n).sortedArray()
        return sorted[minOf(n - 1, n * p / 100)] / 1e6
    }

    /** Share of recent frames longer than one refresh, 0..100. */
    fun recentSlowPercent(): Int {
        val n = minOf(totalFrames, recent.size.toLong()).toInt()
        if (n == 0) return 0
        var slow = 0
        for (i in 0 until n) if (recent[i] > refreshNanos) slow++
        return slow * 100 / n
    }
}
