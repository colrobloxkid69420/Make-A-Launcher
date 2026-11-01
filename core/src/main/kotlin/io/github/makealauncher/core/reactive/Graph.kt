package io.github.makealauncher.core.reactive

/**
 * The reactive data graph: sources hold data (time ticks, battery level, globals), effects
 * read them, and an effect re-runs only when something it actually read has changed.
 *
 * Dependencies are recorded automatically while an effect runs, so a binding that shows
 * `{battery.level}` never wakes up for a clock tick. Everything is single-threaded
 * (the UI thread on Android); changes queue effects and [flush] runs them once per frame.
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
            if (e.disposed) continue
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

    protected fun track() {
        val o = graph.current ?: return
        if (observers.add(o)) o.deps.add(this)
    }

    protected fun notifyObservers() {
        if (observers.isEmpty()) return
        for (o in observers.toTypedArray()) o.onDependencyChanged()
    }
}

internal interface Observer {
    val deps: MutableSet<Dependency>
    fun onDependencyChanged()
}

internal fun Observer.clearDeps() {
    for (d in deps) d.observers.remove(this)
    deps.clear()
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

    private val observer = object : Observer {
        override val deps = LinkedHashSet<Dependency>()

        override fun onDependencyChanged() {
            if (stale) return
            stale = true
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
            observer.clearDeps()
            val prev = graph.current
            graph.current = observer
            try {
                cached = compute()
            } finally {
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

    private val observer = object : Observer {
        override val deps = LinkedHashSet<Dependency>()

        override fun onDependencyChanged() {
            if (!disposed) graph.schedule(this@Effect)
        }
    }

    /** How many sources and computeds this effect read on its last run. */
    val dependencyCount: Int get() = observer.deps.size

    internal fun execute() {
        observer.clearDeps()
        val prev = graph.current
        graph.current = observer
        try {
            run()
        } finally {
            graph.current = prev
            graph.effectRuns++
        }
    }

    fun dispose() {
        disposed = true
        observer.clearDeps()
    }
}
