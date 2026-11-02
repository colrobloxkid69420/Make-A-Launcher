package io.github.makealauncher.core.energy

import java.util.Locale
import kotlin.math.abs

/**
 * One energy reading. Counters are cumulative since the process started ([session]), so a
 * restart is detected by a change of session and never produces negative deltas.
 *
 * Battery current and charge are device-wide (the whole phone, including the screen), so they
 * are only meaningful when comparing runs under the same conditions, such as the same
 * brightness with a blank preset as the baseline.
 */
data class EnergySample(
    /** Wall-clock milliseconds, so intervals stay meaningful across process restarts and reboots. */
    val timeMs: Long,
    /** Process start time; identifies which run the counters belong to. */
    val session: Long,
    /** True while the launcher is on screen. */
    val visible: Boolean,
    /** CPU time used by the whole process (all threads, including rendering), in ms. */
    val cpuMs: Long,
    /** Frames the window rendered. */
    val frames: Long,
    /** Engine frames that had work to do. */
    val engineUpdates: Long,
    val bindingRuns: Long,
    val redraws: Long,
    val moves: Long,
    /** Timer ticks and broadcasts handled. */
    val wakeups: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    /** Instantaneous device current in µA (positive = draining), or null if unsupported. */
    val currentMicroAmps: Long?,
    /** Device charge counter in µAh, or null if unsupported. */
    val chargeMicroAh: Long?,
    /** CPU time of the main (UI) thread, which runs the engine, in ms; null if unreadable. */
    val uiCpuMs: Long? = null,
    /** CPU time of Android's render thread, which turns drawings into GPU commands, in ms. */
    val renderCpuMs: Long? = null,
    /** GPU time spent on this app's frames, in ms. */
    val gpuMs: Long? = null,
    /** Frames that missed Android's frame deadline (what shows up as stutter). */
    val lateFrames: Long = 0,
)

data class EnergySummary(
    val seconds: Double,
    /** Percent of one CPU core. */
    val cpuPercent: Double,
    val framesPerSecond: Double,
    val engineUpdatesPerSecond: Double,
    val bindingRunsPerSecond: Double,
    val redrawsPerSecond: Double,
    val movesPerSecond: Double,
    val wakeupsPerMinute: Double,
    /** Average device current while not charging, in mA; null if unknown. */
    val averageCurrentMilliAmps: Double?,
    /** Device charge used while not charging, in mAh; null if unknown. */
    val milliAmpHoursUsed: Double?,
    /** Battery drop per hour while not charging; null if the window is too short to tell. */
    val batteryPercentPerHour: Double?,
    val uiCpuPercent: Double? = null,
    val renderCpuPercent: Double? = null,
    val gpuMsPerFrame: Double? = null,
    /** Share of frames that missed Android's deadline, 0..100. */
    val latePercent: Double = 0.0,
) {
    fun describe(): List<String> = buildList {
        add("Duration: ${duration(seconds)}")
        val split = listOfNotNull(
            uiCpuPercent?.let { "UI thread ${f(it, 1)}%" },
            renderCpuPercent?.let { "render thread ${f(it, 1)}%" },
        )
        add("CPU: ${f(cpuPercent, 1)}% of one core" + if (split.isEmpty()) "" else " (${split.joinToString(", ")})")
        add(
            "Frames: ${f(framesPerSecond, 1)}/s · late ${f(latePercent, 1)}%" +
                (gpuMsPerFrame?.let { " · GPU ${f(it, 1)} ms/frame" } ?: ""),
        )
        add("Engine updates: ${f(engineUpdatesPerSecond, 1)}/s")
        add("Per second: ${f(bindingRunsPerSecond, 1)} bindings · ${f(redrawsPerSecond, 1)} redraws · ${f(movesPerSecond, 0)} moves")
        add("Wakeups: ${f(wakeupsPerMinute, 1)}/min")
        averageCurrentMilliAmps?.let { add("Device current: ${f(it, 0)} mA (whole phone)") }
        milliAmpHoursUsed?.let { add("Device charge used: ${f(it, 1)} mAh") }
        batteryPercentPerHour?.let { add("Battery: ${f(it, 1)}%/hour") }
    }
}

/** What happened while the launcher was hidden. All of it should stay at or near zero. */
data class BackgroundReport(
    val hiddenSeconds: Double,
    val cpuMs: Long,
    val frames: Long,
    val engineUpdates: Long,
    val wakeups: Long,
) {
    val clean: Boolean get() = frames == 0L && engineUpdates == 0L && wakeups == 0L

    fun describe(): String =
        "Hidden ${duration(hiddenSeconds)}: ${engineUpdates} engine updates, ${frames} frames, " +
            "${wakeups} wakeups, ${cpuMs} ms CPU" + if (clean) " (clean)" else " (should all be 0)"
}

/** A rolling log of samples with summaries and CSV export. */
class EnergyLog(private val capacity: Int = 5000) {
    private val list = ArrayList<EnergySample>()
    val samples: List<EnergySample> get() = list

    fun add(sample: EnergySample) {
        list += sample
        if (list.size > capacity) list.subList(0, list.size - capacity).clear()
    }

    fun addAll(samples: Collection<EnergySample>) = samples.forEach(::add)

    fun clear() = list.clear()

    /** Samples taken at or after [timeMs]. */
    fun since(timeMs: Long): List<EnergySample> = list.filter { it.timeMs >= timeMs }

    fun toCsv(last: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        val from = maxOf(0, list.size - last)
        for (i in from until list.size) sb.append(toCsvLine(list[i])).append('\n')
        return sb.toString()
    }

    companion object {
        const val CSV_HEADER =
            "time_ms,session,visible,cpu_ms,frames,engine_updates,binding_runs,redraws,moves,wakeups,battery_pct,charging,current_ua,charge_uah," +
                "ui_cpu_ms,render_cpu_ms,gpu_ms,late_frames"

        fun toCsvLine(s: EnergySample): String = listOf(
            s.timeMs, s.session, if (s.visible) 1 else 0, s.cpuMs, s.frames, s.engineUpdates, s.bindingRuns,
            s.redraws, s.moves, s.wakeups, s.batteryPercent, if (s.charging) 1 else 0,
            s.currentMicroAmps ?: "", s.chargeMicroAh ?: "",
            s.uiCpuMs ?: "", s.renderCpuMs ?: "", s.gpuMs ?: "", s.lateFrames,
        ).joinToString(",")

        /**
         * Parses lines written by [toCsvLine], including the shorter lines older versions wrote;
         * malformed lines are skipped.
         */
        fun fromCsv(text: String): List<EnergySample> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("time_ms") }
            .mapNotNull { line ->
                val p = line.split(',')
                if (p.size != 14 && p.size != 18) return@mapNotNull null
                val extended = p.size == 18
                try {
                    EnergySample(
                        timeMs = p[0].toLong(), session = p[1].toLong(), visible = p[2] == "1",
                        cpuMs = p[3].toLong(), frames = p[4].toLong(), engineUpdates = p[5].toLong(),
                        bindingRuns = p[6].toLong(), redraws = p[7].toLong(), moves = p[8].toLong(),
                        wakeups = p[9].toLong(), batteryPercent = p[10].toInt(), charging = p[11] == "1",
                        currentMicroAmps = p[12].toLongOrNull(), chargeMicroAh = p[13].toLongOrNull(),
                        uiCpuMs = if (extended) p[14].toLongOrNull() else null,
                        renderCpuMs = if (extended) p[15].toLongOrNull() else null,
                        gpuMs = if (extended) p[16].toLongOrNull() else null,
                        lateFrames = if (extended) p[17].toLong() else 0L,
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }.toList()

        /**
         * Summarises the intervals between consecutive samples. [visible] limits it to time spent
         * on screen (true) or hidden (false), judged by the sample that starts each interval.
         * Returns null if there is no usable interval.
         */
        fun summarize(samples: List<EnergySample>, visible: Boolean? = null): EnergySummary? {
            var ms = 0L
            var cpu = 0L
            var frames = 0L
            var updates = 0L
            var bindings = 0L
            var redraws = 0L
            var moves = 0L
            var wakeups = 0L
            var dischargeMs = 0L
            var chargeUsed = 0L
            var chargeKnown = false
            var pctDrop = 0
            var late = 0L
            val ui = Part()
            val render = Part()
            val gpu = Part()
            for (i in 1 until samples.size) {
                val a = samples[i - 1]
                val b = samples[i]
                if (visible != null && a.visible != visible) continue
                val dt = b.timeMs - a.timeMs
                if (dt <= 0) continue
                if (a.session == b.session) {
                    ms += dt
                    cpu += b.cpuMs - a.cpuMs
                    frames += b.frames - a.frames
                    updates += b.engineUpdates - a.engineUpdates
                    bindings += b.bindingRuns - a.bindingRuns
                    redraws += b.redraws - a.redraws
                    moves += b.moves - a.moves
                    wakeups += b.wakeups - a.wakeups
                    late += b.lateFrames - a.lateFrames
                    ui.add(a.uiCpuMs, b.uiCpuMs, dt, b.frames - a.frames)
                    render.add(a.renderCpuMs, b.renderCpuMs, dt, b.frames - a.frames)
                    gpu.add(a.gpuMs, b.gpuMs, dt, b.frames - a.frames)
                }
                if (!a.charging && !b.charging) {
                    dischargeMs += dt
                    pctDrop += a.batteryPercent - b.batteryPercent
                    if (a.chargeMicroAh != null && b.chargeMicroAh != null) {
                        chargeUsed += a.chargeMicroAh - b.chargeMicroAh
                        chargeKnown = true
                    }
                }
            }
            if (ms == 0L) return null
            val seconds = ms / 1000.0
            val currents = samples
                .filter { (visible == null || it.visible == visible) && !it.charging }
                .mapNotNull { it.currentMicroAmps }
            val hours = dischargeMs / 3_600_000.0
            return EnergySummary(
                seconds = seconds,
                cpuPercent = cpu * 100.0 / ms,
                framesPerSecond = frames / seconds,
                engineUpdatesPerSecond = updates / seconds,
                bindingRunsPerSecond = bindings / seconds,
                redrawsPerSecond = redraws / seconds,
                movesPerSecond = moves / seconds,
                wakeupsPerMinute = wakeups / (seconds / 60.0),
                averageCurrentMilliAmps = if (currents.isEmpty()) null else currents.average() / 1000.0,
                milliAmpHoursUsed = if (chargeKnown) maxOf(0L, chargeUsed) / 1000.0 else null,
                // Battery percent only moves in whole steps, so it needs a long window to mean anything.
                batteryPercentPerHour = if (hours >= 0.5) pctDrop / hours else null,
                uiCpuPercent = ui.percent(),
                renderCpuPercent = render.percent(),
                gpuMsPerFrame = gpu.perFrame(),
                latePercent = if (frames > 0) late * 100.0 / frames else 0.0,
            )
        }

        /** Totals for a counter some samples may lack (older logs, unreadable /proc). */
        private class Part {
            var value = 0L
            var ms = 0L
            var frames = 0L

            fun add(a: Long?, b: Long?, dt: Long, df: Long) {
                if (a == null || b == null || b < a) return
                value += b - a
                ms += dt
                frames += df
            }

            fun percent(): Double? = if (ms > 0) value * 100.0 / ms else null

            fun perFrame(): Double? = if (frames > 0) value.toDouble() / frames else null
        }

        /** Totals for intervals that started while hidden, within one process session. */
        fun background(samples: List<EnergySample>): BackgroundReport {
            var ms = 0L
            var cpu = 0L
            var frames = 0L
            var updates = 0L
            var wakeups = 0L
            for (i in 1 until samples.size) {
                val a = samples[i - 1]
                val b = samples[i]
                if (a.visible || a.session != b.session || b.timeMs <= a.timeMs) continue
                ms += b.timeMs - a.timeMs
                cpu += b.cpuMs - a.cpuMs
                frames += b.frames - a.frames
                updates += b.engineUpdates - a.engineUpdates
                wakeups += b.wakeups - a.wakeups
            }
            return BackgroundReport(ms / 1000.0, cpu, frames, updates, wakeups)
        }

        /**
         * Android documents BATTERY_PROPERTY_CURRENT_NOW in µA, but some phones report mA and
         * the sign convention varies. Returns µA as a positive drain, or null if unsupported.
         */
        fun normalizeCurrent(raw: Long, charging: Boolean): Long? {
            if (raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong() || raw == 0L) return null
            val micro = if (abs(raw) < 10_000) raw * 1000 else raw
            return if (charging) -abs(micro) else abs(micro)
        }
    }
}

private fun f(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

internal fun duration(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60}m ${s % 60}s"
        else -> "${s}s"
    }
}
