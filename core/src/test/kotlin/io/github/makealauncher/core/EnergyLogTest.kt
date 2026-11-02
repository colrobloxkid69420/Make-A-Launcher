package io.github.makealauncher.core

import io.github.makealauncher.core.energy.EnergyLog
import io.github.makealauncher.core.energy.EnergySample
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyLogTest {
    private fun s(
        t: Long,
        visible: Boolean = true,
        cpu: Long = 0,
        frames: Long = 0,
        updates: Long = 0,
        wakeups: Long = 0,
        pct: Int = 80,
        charging: Boolean = false,
        current: Long? = 300_000,
        charge: Long? = 3_000_000,
        session: Long = 1,
    ) = EnergySample(t, session, visible, cpu, frames, updates, updates, 0, 0, wakeups, pct, charging, current, charge)

    @Test
    fun summarizesRatesAndCharge() {
        val samples = listOf(
            s(0, cpu = 0, frames = 0, wakeups = 0, charge = 3_000_000),
            s(60_000, cpu = 600, frames = 120, wakeups = 1, charge = 2_995_000, current = 320_000),
            s(120_000, cpu = 1200, frames = 240, wakeups = 2, charge = 2_990_000, current = 340_000),
        )
        val sum = assertNotNull(EnergyLog.summarize(samples))
        assertEquals(120.0, sum.seconds)
        assertEquals(1.0, sum.cpuPercent, 1e-9) // 1.2 s of CPU in 120 s
        assertEquals(2.0, sum.framesPerSecond, 1e-9)
        assertEquals(1.0, sum.wakeupsPerMinute, 1e-9)
        assertEquals(320.0, sum.averageCurrentMilliAmps!!, 1e-9)
        assertEquals(10.0, sum.milliAmpHoursUsed!!, 1e-9)
        assertNull(sum.batteryPercentPerHour, "two minutes is too short for whole-percent steps")
    }

    @Test
    fun visibleAndHiddenTimeAreSeparated() {
        val samples = listOf(
            s(0, visible = true, cpu = 0),
            s(10_000, visible = false, cpu = 100), // went to background
            s(3_610_000, visible = true, cpu = 150, pct = 78), // came back an hour later
            s(3_620_000, visible = true, cpu = 250, pct = 78),
        )
        val shown = assertNotNull(EnergyLog.summarize(samples, visible = true))
        assertEquals(20.0, shown.seconds)
        assertEquals(1.0, shown.cpuPercent, 1e-9)
        val hidden = assertNotNull(EnergyLog.summarize(samples, visible = false))
        assertEquals(3600.0, hidden.seconds)
        assertEquals(2.0, hidden.batteryPercentPerHour!!, 1e-9)

        val bg = EnergyLog.background(samples)
        assertEquals(3600.0, bg.hiddenSeconds)
        assertEquals(50, bg.cpuMs)
        assertTrue(bg.clean)
    }

    @Test
    fun backgroundWorkIsFlagged() {
        val bg = EnergyLog.background(listOf(s(0, visible = false), s(60_000, visible = true, wakeups = 3)))
        assertFalse(bg.clean)
        assertEquals(3, bg.wakeups)
    }

    @Test
    fun processRestartDoesNotProduceNegativeDeltas() {
        val samples = listOf(
            s(0, cpu = 5000, session = 1),
            s(60_000, cpu = 10, session = 2), // counters reset after a restart
            s(120_000, cpu = 610, session = 2),
        )
        val sum = assertNotNull(EnergyLog.summarize(samples))
        assertEquals(60.0, sum.seconds)
        assertEquals(1.0, sum.cpuPercent, 1e-9)
    }

    @Test
    fun chargingTimeIsExcludedFromDrain() {
        val samples = listOf(
            s(0, charge = 1_000_000),
            s(60_000, charging = true, charge = 1_200_000, current = -900_000),
            s(120_000, charging = true, charge = 1_400_000, current = -900_000),
        )
        val sum = assertNotNull(EnergyLog.summarize(samples))
        assertEquals(300.0, sum.averageCurrentMilliAmps!!, 1e-9) // only the non-charging sample
        assertNull(sum.milliAmpHoursUsed)
    }

    @Test
    fun csvRoundTrips() {
        val log = EnergyLog()
        log.add(s(1000, current = null, charge = null))
        log.add(s(2000, visible = false, cpu = 42))
        val parsed = EnergyLog.fromCsv(log.toCsv())
        assertEquals(log.samples, parsed)
        assertEquals(1, EnergyLog.fromCsv(log.toCsv(last = 1)).size)
    }

    @Test
    fun logIsCapped() {
        val log = EnergyLog(capacity = 3)
        repeat(5) { log.add(s(it * 1000L)) }
        assertEquals(listOf(2000L, 3000L, 4000L), log.samples.map { it.timeMs })
    }

    @Test
    fun currentNormalisation() {
        assertEquals(350_000, EnergyLog.normalizeCurrent(-350_000, charging = false))
        assertEquals(350_000, EnergyLog.normalizeCurrent(350, charging = false)) // phone reporting mA
        assertEquals(-500_000, EnergyLog.normalizeCurrent(500_000, charging = true))
        assertNull(EnergyLog.normalizeCurrent(Int.MIN_VALUE.toLong(), charging = false))
        assertNull(EnergyLog.normalizeCurrent(0, charging = false))
    }

    @Test
    fun threadSplitGpuAndLateFrames() {
        val a = s(0, cpu = 0, frames = 0).copy(uiCpuMs = 0, renderCpuMs = 0, gpuMs = 0, lateFrames = 0)
        val b = s(10_000, cpu = 6000, frames = 600).copy(uiCpuMs = 1500, renderCpuMs = 4000, gpuMs = 2400, lateFrames = 6)
        val sum = assertNotNull(EnergyLog.summarize(listOf(a, b)))
        assertEquals(60.0, sum.cpuPercent, 1e-9)
        assertEquals(15.0, sum.uiCpuPercent!!, 1e-9)
        assertEquals(40.0, sum.renderCpuPercent!!, 1e-9)
        assertEquals(4.0, sum.gpuMsPerFrame!!, 1e-9)
        assertEquals(1.0, sum.latePercent, 1e-9)
        assertTrue(sum.describe().any { "render thread 40.0%" in it })
    }

    @Test
    fun olderLogLinesStillParse() {
        val old = "1000,1,1,10,20,3,4,5,6,7,80,0,300000,3000000"
        val parsed = EnergyLog.fromCsv(EnergyLog.CSV_HEADER + "\n" + old + "\n")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].uiCpuMs)
        assertEquals(0L, parsed[0].lateFrames)
        // A mix of old and new samples summarises without the split rather than failing.
        val sum = assertNotNull(EnergyLog.summarize(listOf(parsed[0], s(11_000, cpu = 110).copy(uiCpuMs = 5))))
        assertNull(sum.uiCpuPercent)
    }

    @Test
    fun procStatParsing() {
        val line = "12345 (Render Thread) S 1 2 3 4 5 6 7 8 9 10 250 75 0 0 20 0 1 0 100 0 0"
        assertEquals(325L, io.github.makealauncher.core.energy.ProcStat.cpuTicks(line))
        assertNull(io.github.makealauncher.core.energy.ProcStat.cpuTicks("garbage"))
    }

    @Test
    fun threadGroupsAndBusiestThreads() {
        val ps = io.github.makealauncher.core.energy.ProcStat
        assertEquals("Jit thread pool", ps.threadName("77 (Jit thread pool) S 1 2 3"))
        assertEquals("binder", ps.groupName("binder:1234_5"))
        assertEquals("hwuiTask", ps.groupName("hwuiTask1"))
        assertEquals("RenderThread", ps.groupName("RenderThread"))
        val before = mapOf(1 to ("main" to 100L), 2 to ("binder:9_1" to 10L), 3 to ("Jit thread pool" to 50L))
        val after = mapOf(
            1 to ("main" to 900L),
            2 to ("binder:9_1" to 30L),
            3 to ("Jit thread pool" to 450L),
            4 to ("binder:9_2" to 25L), // started during the test
        )
        assertEquals(
            listOf("Jit thread pool" to 400L, "binder" to 45L),
            ps.busiestGroups(before, after, exclude = setOf(1)),
        )
    }
}
