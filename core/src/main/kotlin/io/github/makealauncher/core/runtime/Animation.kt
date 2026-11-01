package io.github.makealauncher.core.runtime

import io.github.makealauncher.core.scene.AnimSpec
import io.github.makealauncher.core.scene.Easing

/** Pure functions for scroll animations, so they're cheap and easy to test. */
object Animation {
    /** Progress 0..1 of [input] across [range] (which may run backwards), after easing. */
    fun progress(input: Float, range: ClosedFloatingPointRange<Float>, easing: Easing): Float {
        val span = range.endInclusive - range.start
        val t = if (span == 0f) 0f else ((input - range.start) / span).coerceIn(0f, 1f)
        return ease(t, easing)
    }

    fun ease(t: Float, easing: Easing): Float = when (easing) {
        Easing.LINEAR -> t
        Easing.IN -> t * t
        Easing.OUT -> 1f - (1f - t) * (1f - t)
        Easing.IN_OUT -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f
    }

    /** Value of a track whose keyframes are spread evenly over progress 0..1. */
    fun sample(values: FloatArray, t: Float): Float {
        if (values.size == 1) return values[0]
        val pos = t * (values.size - 1)
        val i = pos.toInt().coerceIn(0, values.size - 2)
        val frac = pos - i
        return values[i] + (values[i + 1] - values[i]) * frac
    }

    fun rangeFor(spec: AnimSpec, pages: Int): ClosedFloatingPointRange<Float> =
        spec.range ?: (0f..maxOf(1, pages - 1).toFloat())
}
