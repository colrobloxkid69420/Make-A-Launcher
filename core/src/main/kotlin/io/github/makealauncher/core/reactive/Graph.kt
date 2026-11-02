package io.github.makealauncher.core.reactive

/**
 * The reactive data graph: sources hold data (time ticks, battery level, globals), effects
 * read them, and an effect re-runs only when something it actually read has changed.
 *
 * Dependencies are recorded automatically while an effect runs, so a binding that shows
 * `{battery.level}` never wakes up for a clock tick. Everything is single-threaded
 * (the UI thread on Android); changes queue effects and [flush] runs them once per frame.
 *
 * Effects can be paused (their layer is off screen): they stop listening, and on resume they
 * re-run only if something they read changed in the meantime. A source knows whether anyone
 * is listening ([Dependency.onActiveChanged]), so data that nobody shows can stop updating.
 */
class Graph {
    internal var current: Observer? = null
    private val queue = LinkedHashSet<Effect>()

    /** Called when the first effect is queued, so the host can request a frame. */
    var onSchedule: (() -> Unit)? = null

    /** Total effect runs since creation; the performance inspector reports this. */
    var effectRuns: Long = 0
        internal set

    val hasPending: Boolean get() = queue.isNotEmpty()

    fun <T> source(initial: T): Source<T> = Source(this, initial)

    fun <T> computed(compute: () -> T): Computed<T> = Computed(this, compute)

    /** Creates an effect and runs it once immediately to record its dependencies. */
    fun effect(run: () -> Unit): Effect = Effect(this, run).also { it.execute() }

    /** Runs [block] without recording dependencies. */
    fun <T> untracked(block: () -> T): T {
        val prev = current
        current = null
        try {
            return block()
        } finally {
            current = prev
        }
    }

    /** Runs every queued effect. Returns how many ran. */
    fun flush(): Int {
        var ran = 0
        while (queue.isNotEmpty()) {
            val e = queue.first()
            queue.remove(e)
            if (e.disposed || e.paused) continue
            e.execute()
            ran++
            check(ran < MAX_RUNS_PER_FLUSH) {
                "effects keep re-triggering each other; a binding probably writes to something it reads"
            }
        }
        return ran
    }

    internal fun schedule(e: Effect) {
        val wasEmpty = queue.isEmpty()
        queue += e
        if (wasEmpty) onSchedule?.invoke()
    }

    /** Removes [e] from the queue; returns true if it was waiting to run. */
    internal fun unschedule(e: Effect): Boolean = queue.remove(e)

    private companion object {
        const val MAX_RUNS_PER_FLUSH = 100_000
    }
}

interface Signal<out T> {
    /** Reads the value and records a dependency when called inside an effect or computed. */
    val value: T

    /** Reads the value without recording a dependency. */
    fun peek(): T
}

abstract class Dependency internal constructor(internal val graph: Graph) {
    internal val observers = LinkedHashSet<Observer>()

    val observerCount: Int get() = observers.size

    /** Increases whenever the value may have changed; paused effects compare it on resume. */
    var version: Long = 0
        internal set

    /**
     * Called with true when the first observer starts listening and false when the last one
     * stops. Data sources use it to run only while someone shows their data.
     */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    protected fun track() {
        val o = graph.current ?: return
        val collecting = o.collecting ?: return
        if (collecting.add(this)) addObserver(o)
    }

    internal fun addObserver(o: Observer) {
        if (observers.add(o) && observers.size == 1) onActiveChanged?.invoke(true)
    }

    internal fun removeObserver(o: Observer) {
        if (observers.remove(o) && observers.isEmpty()) onActiveChanged?.invoke(false)
    }

    protected fun notifyObservers() {
        if (observers.isEmpty()) return
        for (o in observers.toTypedArray()) o.onDependencyChanged()
    }
}

/**
 * Something that reads dependencies. Tracking is diff-based: a re-run keeps the subscriptions
 * it still uses and drops only the ones it stopped using, so a dependency that's read on every
 * run never flips between active and inactive.
 */
internal abstract class Observer {
    var deps: LinkedHashSet<Dependency> = LinkedHashSet()
    private var spare: LinkedHashSet<Dependency> = LinkedHashSet()
    var collecting: LinkedHashSet<Dependency>? = null

    abstract fun onDependencyChanged()

    fun beginTracking() {
        spare.clear()
        collecting = spare
    }

    fun endTracking() {
        val now = spare
        for (d in deps) if (d !in now) d.removeObserver(this)
        spare = deps
        deps = now
        collecting = null
    }

    fun clearDeps() {
        val old = deps.toTypedArray()
        deps.clear()
        for (d in old) d.removeObserver(this)
    }
}

class Source<T> internal constructor(graph: Graph, initial: T) : Dependency(graph), Signal<T> {
    private var v: T = initial

    override val value: T
        get() {
            track()
            return v
        }

    override fun peek(): T = v

    /** Sets the value; observers are notified only if it actually changed. */
    fun set(newValue: T) {
        if (newValue == v) return
        v = newValue
        version++
        notifyObservers()
    }
}

/** A derived value, recomputed lazily the next time it's read after a dependency changes. */
class Computed<T> internal constructor(
    graph: Graph,
    private val compute: () -> T,
) : Dependency(graph), Signal<T> {
    private var stale = true
    private var cached: Any? = null

    private val observer = object : Observer() {
        override fun onDependencyChanged() {
            if (stale) return
            stale = true
            version++
            notifyObservers()
        }
    }

    override val value: T
        get() {
            track()
            return refresh()
        }

    override fun peek(): T = refresh()

    @Suppress("UNCHECKED_CAST")
    private fun refresh(): T {
        if (stale) {
            val prev = graph.current
            graph.current = observer
            observer.beginTracking()
            try {
                cached = compute()
            } finally {
                observer.endTracking()
                graph.current = prev
            }
            stale = false
        }
        return cached as T
    }
}

class Effect internal constructor(
    private val graph: Graph,
    private val run: () -> Unit,
) {
    var disposed = false
        private set

    /** A paused effect doesn't listen to anything and never runs until resumed. */
    var paused = false
        private set

    private var pausedDeps: Array<Dependency> = EMPTY
    private var pausedVersions = LongArray(0)
    private var missedRun = false

    private val observer = object : Observer() {
        override fun onDependencyChanged() {
            if (!disposed && !paused) graph.schedule(this@Effect)
        }
    }

    /** How many sources and computeds this effect read on its last run. */
    val dependencyCount: Int get() = observer.deps.size

    internal fun execute() {
        val prev = graph.current
        graph.current = observer
        observer.beginTracking()
        try {
            run()
        } finally {
            observer.endTracking()
            graph.current = prev
            graph.effectRuns++
        }
    }

    /** Stops listening. Cheap; the effect remembers what it read and those values' versions. */
    fun pause() {
        if (paused || disposed) return
        paused = true
        pausedDeps = observer.deps.toTypedArray()
        pausedVersions = LongArray(pausedDeps.size) { pausedDeps[it].version }
        missedRun = graph.unschedule(this)
        observer.clearDeps()
    }

    /**
     * Listens again. Re-runs (on the next [Graph.flush]) only if something it read changed
     * while it was paused, or if it was waiting to run when it was paused.
     */
    fun resume() {
        if (!paused || disposed) return
        paused = false
        val deps = pausedDeps
        val versions = pausedVersions
        pausedDeps = EMPTY
        pausedVersions = LongArray(0)
        // Subscribe first: becoming active can make a source refresh itself (e.g. the clock),
        // and that refresh must be seen by the version check below.
        for (d in deps) {
            observer.deps.add(d)
            d.addObserver(observer)
        }
        var changed = missedRun
        missedRun = false
        for (i in deps.indices) if (deps[i].version != versions[i]) changed = true
        if (changed) graph.schedule(this)
    }

    fun dispose() {
        disposed = true
        pausedDeps = EMPTY
        observer.clearDeps()
        graph.unschedule(this)
    }

    private companion object {
        val EMPTY = emptyArray<Dependency>()
    }
}
