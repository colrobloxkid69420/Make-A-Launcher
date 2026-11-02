package io.github.makealauncher.core.data

import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.reactive.Source
import java.time.ZonedDateTime

/**
 * Live data that presets can read. The platform pushes values in (Android broadcasts on the
 * phone, fixed values in tests); the engine only ever reads them through the graph.
 */
class DataSources(graph: Graph, clock: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    val time = TimeData(graph, clock)
    val battery = BatteryData(graph)
}

/**
 * Time is exposed as two tick counters rather than a clock value, so a binding that shows
 * hours and minutes depends only on [minuteTick] and wakes up once a minute, while one that
 * shows seconds depends on [secondTick]. The platform only runs a seconds timer while
 * [secondTick] has observers.
 */
class TimeData(graph: Graph, val clock: () -> ZonedDateTime) {
    val minuteTick: Source<Long> = graph.source(0L)
    val secondTick: Source<Long> = graph.source(0L)

    /** Call on every minute boundary, and after time or time-zone changes. */
    fun onMinute() = minuteTick.set(minuteTick.peek() + 1)

    fun onSecond() = secondTick.set(secondTick.peek() + 1)

    val secondsNeeded: Boolean get() = secondTick.observerCount > 0

    /**
     * Called when something on screen starts (true) or stops (false) showing seconds, for
     * example when a page with a seconds display scrolls into view. The platform starts or
     * stops its seconds timer here. When it starts it should call [onSecond] right away, so
     * the display is up to date in the frame it appears.
     */
    var onSecondsNeededChanged: ((Boolean) -> Unit)? = null

    init {
        secondTick.onActiveChanged = { active -> onSecondsNeededChanged?.invoke(active) }
    }
}

class BatteryData(graph: Graph) {
    /** 0..100 */
    val level: Source<Int> = graph.source(100)
    val charging: Source<Boolean> = graph.source(false)
}
