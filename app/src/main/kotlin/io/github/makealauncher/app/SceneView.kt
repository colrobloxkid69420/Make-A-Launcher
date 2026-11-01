package io.github.makealauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import io.github.makealauncher.core.runtime.SceneRuntime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Hosts a scene. It owns the frame loop and the horizontal page scrolling; the engine and
 * [SceneRenderer] do the rest.
 *
 * Frames are requested only when there is work: a data change (the graph asks for a frame),
 * a scroll or fling in progress, or a size change. With nothing changing, the view draws
 * nothing and costs no CPU.
 */
class SceneView(context: Context) : View(context), Choreographer.FrameCallback {

    private var runtime: SceneRuntime? = null
    private var renderer: SceneRenderer? = null
    private val choreographer = Choreographer.getInstance()
    private var frameRequested = false

    /** Nanoseconds the engine spent in the last frame (update + applying changes). */
    var lastWorkNanos = 0L
        private set
    var totalWorkNanos = 0L
        private set
    var workFrames = 0L
        private set

    var onLongPress: (() -> Unit)? = null

    // ---- scrolling state -----------------------------------------------------------------
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity
    private val maxFling = config.scaledMaximumFlingVelocity
    private val scroller = OverScroller(context, DecelerateInterpolator(1.6f))
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var scrollPx = 0f

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!dragging) onLongPress?.invoke()
            }
        },
    )

    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
    }

    fun setScene(newRuntime: SceneRuntime, newRenderer: SceneRenderer) {
        runtime?.let { old -> renderer?.release(old) }
        runtime = newRuntime
        renderer = newRenderer
        scroller.forceFinished(true)
        scrollPx = 0f
        if (width > 0) newRuntime.setViewport(width.toFloat(), height.toFloat())
        requestFrame()
    }

    fun requestFrame() {
        if (!frameRequested && isAttachedToWindow) {
            frameRequested = true
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        frameRequested = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val rt = runtime ?: return
        val page = if (oldw > 0) scrollPx / oldw else 0f
        scrollPx = page * w
        rt.setViewport(w.toFloat(), h.toFloat())
        requestFrame()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameRequested = false
        val rt = runtime ?: return
        val r = renderer ?: return
        if (width == 0) return
        val flinging = scroller.computeScrollOffset()
        if (flinging) scrollPx = scroller.currX.toFloat()
        rt.setScroll(scrollPx / width)

        if (rt.needsUpdate) {
            val t0 = System.nanoTime()
            if (rt.update()) {
                r.apply(rt)
                invalidate()
            }
            lastWorkNanos = System.nanoTime() - t0
            totalWorkNanos += lastWorkNanos
            workFrames++
        }
        if (flinging || !scroller.isFinished || rt.needsUpdate) requestFrame()
    }

    override fun onDraw(canvas: Canvas) {
        val rt = runtime ?: return
        if (!canvas.isHardwareAccelerated) {
            canvas.drawColor(Color.BLACK)
            canvas.drawText("Hardware acceleration is required.", 24f, height / 2f, messagePaint)
            return
        }
        val root = renderer?.rootNode(rt) ?: return
        canvas.drawRenderNode(root)
    }

    // ---- touch: horizontal paging --------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val rt = runtime ?: return false
        gestures.onTouchEvent(e)
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x
                lastX = e.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > touchSlop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val maxScroll = (rt.pages - 1) * width.toFloat()
                    scrollPx = (scrollPx - (e.x - lastX)).coerceIn(0f, maxScroll)
                    requestFrame()
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt.computeCurrentVelocity(1000, maxFling.toFloat())
                    settle(vt.xVelocity, rt.pages)
                }
                dragging = false
                vt.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun settle(vx: Float, pages: Int) {
        val w = width.toFloat()
        val position = scrollPx / w
        val target = when {
            vx < -minFling -> ceil(position)
            vx > minFling -> floor(position)
            else -> position.roundToInt().toFloat()
        }.coerceIn(0f, (pages - 1).toFloat())
        val dx = (target * w - scrollPx).roundToInt()
        val duration = (220 + abs(dx) * 120 / w.coerceAtLeast(1f)).toInt().coerceAtMost(420)
        scroller.startScroll(scrollPx.roundToInt(), 0, dx, 0, duration)
        requestFrame()
    }
}
