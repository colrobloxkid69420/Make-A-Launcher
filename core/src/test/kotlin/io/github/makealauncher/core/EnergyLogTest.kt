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
}
