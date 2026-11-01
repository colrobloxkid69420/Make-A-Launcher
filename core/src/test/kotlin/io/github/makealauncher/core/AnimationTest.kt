package io.github.makealauncher.core

import io.github.makealauncher.core.runtime.Animation
import io.github.makealauncher.core.scene.Easing
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AnimationTest {
    @Test
    fun progressClampsAndSupportsReverseRanges() {
        assertEquals(0f, Animation.progress(-1f, 0f..2f, Easing.LINEAR))
        assertEquals(0.5f, Animation.progress(1f, 0f..2f, Easing.LINEAR))
        assertEquals(1f, Animation.progress(5f, 0f..2f, Easing.LINEAR))
        assertEquals(0.25f, Animation.progress(1.5f, 2f..0f, Easing.LINEAR))
    }

    @Test
    fun sampleInterpolatesBetweenEvenlySpacedKeyframes() {
        val v = floatArrayOf(0f, 10f, 0f)
        assertEquals(0f, Animation.sample(v, 0f))
        assertEquals(5f, Animation.sample(v, 0.25f))
        assertEquals(10f, Animation.sample(v, 0.5f))
        assertEquals(0f, Animation.sample(v, 1f))
    }

    @Test
    fun easingCurvesHitTheirEnds() {
        for (e in Easing.entries) {
            assertEquals(0f, Animation.ease(0f, e), e.name)
            assertEquals(1f, Animation.ease(1f, e), e.name)
        }
        assertEquals(0.25f, Animation.ease(0.5f, Easing.IN))
        assertEquals(0.75f, Animation.ease(0.5f, Easing.OUT))
        assertEquals(0.5f, Animation.ease(0.5f, Easing.IN_OUT))
    }
}
