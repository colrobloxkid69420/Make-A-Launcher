package io.github.makealauncher.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.makealauncher.core.energy.BackgroundReport
import io.github.makealauncher.core.energy.EnergyLog
import io.github.makealauncher.core.energy.EnergySample
import java.io.File
import java.io.IOException

/**
 * Records energy samples: the app's own CPU time and work counters, plus the phone's battery
 * level, current draw and charge counter.
 *
 * It samples every [intervalMs] while the launcher is visible, once when it's hidden and once
 * when it comes back, and never while hidden. The interval between "hidden" and "visible"
 * samples is the background audit: the app's counters must not move during it.
 *
 * Samples are appended to a small CSV file so the log survives the app being closed.
 */
class EnergyMonitor(
    private val context: Context,
    private val frames: FrameStats,
    val threads: ThreadCpu,
) {
    val log = EnergyLog(capacity = MAX_SAMPLES)
    private val file = File(context.filesDir, "energy-log.csv")
    private val session = Process.getStartElapsedRealtime()
    private val handler = Handler(Looper.getMainLooper())
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var visible = false

    /** How often to sample while visible. Tests shorten it for a finer current average. */
    var intervalMs = DEFAULT_INTERVAL_MS
        set(value) {
            field = value
            if (visible) {
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, value)
            }
        }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            sample()
            handler.postDelayed(this, intervalMs)
        }
    }

    init {
        load()
    }

    fun onVisible() {
        visible = true
        sample()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, intervalMs)
    }

    fun onHidden() {
        handler.removeCallbacks(tick)
        visible = false
        sample()
    }

    fun sample(): EnergySample {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val s = EnergySample(
            timeMs = System.currentTimeMillis(),
            session = session,
            visible = visible,
            cpuMs = Process.getElapsedCpuTime(),
            frames = frames.totalFrames,
            engineUpdates = EnergyCounters.engineUpdates,
            bindingRuns = EnergyCounters.bindingRuns,
            redraws = EnergyCounters.redraws,
            moves = EnergyCounters.moves,
            wakeups = EnergyCounters.wakeups,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = charging,
            currentMicroAmps = currentMicroAmps(charging),
            chargeMicroAh = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Int.MIN_VALUE && it > 0 }?.toLong(),
            uiCpuMs = threads.uiMs(),
            renderCpuMs = threads.renderMs(),
            gpuMs = frames.gpuNanos / 1_000_000,
            lateFrames = frames.lateFrames,
        )
        log.add(s)
        append(s)
        return s
    }

    /** Device-wide current right now, in µA (positive = draining), or null if unsupported. */
    fun currentMicroAmps(charging: Boolean = false): Long? {
        val raw = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return null
        return EnergyLog.normalizeCurrent(raw.toLong(), charging)
    }

    /** The most recent stretch spent hidden, or null if there hasn't been one this session. */
    fun lastBackground(): BackgroundReport? {
        val s = log.samples
        for (i in s.size - 1 downTo 1) {
            val a = s[i - 1]
            val b = s[i]
            if (!a.visible && b.visible && a.session == b.session) return EnergyLog.background(listOf(a, b))
        }
        return null
    }

    fun clear() {
        log.clear()
        file.delete()
    }

    private fun append(s: EnergySample) {
        try {
            if (!file.exists()) file.writeText(EnergyLog.CSV_HEADER + "\n")
            file.appendText(EnergyLog.toCsvLine(s) + "\n")
        } catch (e: IOException) {
            // The in-memory log still works; persistence is best effort.
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val samples = EnergyLog.fromCsv(file.readText())
            log.addAll(samples)
            if (samples.size > MAX_SAMPLES) file.writeText(log.toCsv())
        } catch (e: IOException) {
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val MAX_SAMPLES = 5000
    }
}
