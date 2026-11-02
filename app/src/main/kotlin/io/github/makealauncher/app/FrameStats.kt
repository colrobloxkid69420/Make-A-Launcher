package io.github.makealauncher.app

import android.os.Handler
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import io.github.makealauncher.core.energy.ProcStat
import java.io.File

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

/**
 * Collects real frame timings from Android's FrameMetrics.
 *
 * A frame is *late* when it finished after Android's own deadline for it (the same rule
 * Android uses to count janky frames since version 12). Frames can take longer than one
 * refresh without being late, because the main thread, render thread and GPU work on
 * different frames at the same time.
 */
class FrameStats {
    private val recentTotal = LongArray(240)
    private val recentLate = BooleanArray(240)

    /** Frames the window rendered since the app started. */
    var totalFrames = 0L
        private set

    /** Frames that missed their deadline. */
    var lateFrames = 0L
        private set

    /** GPU time spent on this app's frames, in ns. */
    var gpuNanos = 0L
        private set

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
        val gpu = metrics.getMetric(FrameMetrics.GPU_DURATION)
        val late = deadline > 0 && total > deadline
        val i = (totalFrames % recentTotal.size).toInt()
        recentTotal[i] = total
        recentLate[i] = late
        totalFrames++
        if (late) lateFrames++
        if (gpu > 0) gpuNanos += gpu
    }

    fun attach(window: Window, handler: Handler) {
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    fun detach(window: Window) {
        window.removeOnFrameMetricsAvailableListener(listener)
    }

    /** Percentile [p] (0..100) of the last few hundred frame times, in ms. */
    fun recentPercentileMs(p: Int): Double {
        val n = minOf(totalFrames, recentTotal.size.toLong()).toInt()
        if (n == 0) return 0.0
        val sorted = recentTotal.copyOf(n).sortedArray()
        return sorted[minOf(n - 1, n * p / 100)] / 1e6
    }

    /** Share of recent frames that missed their deadline, 0..100. */
    fun recentLatePercent(): Int {
        val n = minOf(totalFrames, recentLate.size.toLong()).toInt()
        if (n == 0) return 0
        var late = 0
        for (i in 0 until n) if (recentLate[i]) late++
        return late * 100 / n
    }
}

/**
 * Reads CPU time per thread from /proc, so measurements can tell the engine's work (main
 * thread) apart from Android's drawing work (render thread).
 */
class ThreadCpu {
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    private val mainTid = Process.myPid()
    private var renderTid = -1

    /** Main (UI) thread CPU time in ms, or null if it can't be read. */
    fun uiMs(): Long? = readMs(mainTid)

    /** Render thread CPU time in ms, or null if it can't be read. */
    fun renderMs(): Long? {
        if (renderTid < 0) renderTid = findThread("RenderThread")
        if (renderTid < 0) return null
        return readMs(renderTid) ?: run {
            renderTid = -1
            null
        }
    }

    /** Every thread of this process: id → (name, CPU ms). Used to find what else is busy. */
    fun snapshot(): Map<Int, Pair<String, Long>> {
        val out = HashMap<Int, Pair<String, Long>>()
        val dirs = try {
            File("/proc/self/task").listFiles()
        } catch (e: Exception) {
            null
        } ?: return out
        for (dir in dirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            try {
                val stat = File(dir, "stat").readText()
                val name = ProcStat.threadName(stat) ?: continue
                val ticks = ProcStat.cpuTicks(stat) ?: continue
                out[tid] = name to ticks * 1000 / ticksPerSecond
            } catch (e: Exception) {
                // The thread may have ended; skip it.
            }
        }
        return out
    }

    /** Thread ids of the main and render threads, which results report separately. */
    fun mainAndRenderTids(): Set<Int> {
        if (renderTid < 0) renderTid = findThread("RenderThread")
        return setOf(mainTid, renderTid)
    }

    private fun readMs(tid: Int): Long? = try {
        File("/proc/self/task/$tid/stat").readText().let(ProcStat::cpuTicks)?.let { it * 1000 / ticksPerSecond }
    } catch (e: Exception) {
        null
    }

    private fun findThread(name: String): Int = try {
        File("/proc/self/task").listFiles()
            ?.firstOrNull { dir -> runCatching { File(dir, "comm").readText().trim() == name }.getOrDefault(false) }
            ?.name?.toIntOrNull() ?: -1
    } catch (e: Exception) {
        -1
    }
}
