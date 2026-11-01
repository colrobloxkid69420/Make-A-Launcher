package io.github.makealauncher.core

import io.github.makealauncher.core.reactive.Graph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphTest {
    @Test
    fun effectRerunsOnlyWhenItsSourceChanges() {
        val g = Graph()
        val a = g.source(1)
        val b = g.source(10)
        var runs = 0
        var seen = 0
        g.effect {
            runs++
            seen = a.value
        }
        assertEquals(1, runs)

        b.set(11) // not read by the effect
        assertFalse(g.hasPending)
        assertEquals(0, g.flush())

        a.set(2)
        assertTrue(g.hasPending)
        assertEquals(1, g.flush())
        assertEquals(2, seen)
        assertEquals(2, runs)

        a.set(2) // same value: no work
        assertEquals(0, g.flush())
    }

    @Test
    fun dependenciesAreDynamic() {
        val g = Graph()
        val useA = g.source(true)
        val a = g.source("a")
        val b = g.source("b")
        var out = ""
        g.effect { out = if (useA.value) a.value else b.value }
        useA.set(false)
        g.flush()
        assertEquals("b", out)
        a.set("a2") // no longer a dependency
        assertEquals(0, g.flush())
        b.set("b2")
        g.flush()
        assertEquals("b2", out)
    }

    @Test
    fun computedIsLazyAndShared() {
        val g = Graph()
        val x = g.source(2)
        var computes = 0
        val sq = g.computed {
            computes++
            x.value * x.value
        }
        var r1 = 0
        var r2 = 0
        g.effect { r1 = sq.value }
        g.effect { r2 = sq.value + 1 }
        assertEquals(1, computes)
        x.set(3)
        g.flush()
        assertEquals(9, r1)
        assertEquals(10, r2)
        assertEquals(2, computes)
    }

    @Test
    fun disposedEffectStopsRunning() {
        val g = Graph()
        val a = g.source(0)
        var runs = 0
        val e = g.effect { a.value; runs++ }
        e.dispose()
        a.set(1)
        g.flush()
        assertEquals(1, runs)
        assertEquals(0, a.observerCount)
    }

    @Test
    fun scheduleCallbackFiresOncePerBatch() {
        val g = Graph()
        val a = g.source(0)
        val b = g.source(0)
        var requests = 0
        g.onSchedule = { requests++ }
        g.effect { a.value; b.value }
        a.set(1)
        b.set(1)
        assertEquals(1, requests)
        g.flush()
        a.set(2)
        assertEquals(2, requests)
    }
}
