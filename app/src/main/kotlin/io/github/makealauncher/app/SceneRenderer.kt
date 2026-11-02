package io.github.makealauncher.app

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.text.StaticLayout
import io.github.makealauncher.core.runtime.RuntimeNode
import io.github.makealauncher.core.runtime.SceneRuntime
import io.github.makealauncher.core.scene.ContentSpec
import io.github.makealauncher.core.scene.Fit
import io.github.makealauncher.core.scene.ShapeKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Mirrors the scene as a tree of [RenderNode]s, Android's retained GPU display lists.
 *
 * Each component records its drawing once into its own RenderNode. A group's display list
 * only references its children's RenderNodes, so re-recording a child never touches the
 * parent. Moving, fading, scaling or rotating a component is a property change on its
 * RenderNode: the render thread re-composites the cached drawing without re-recording it.
 *
 * Two things keep scrolling cheap for the render thread and GPU:
 * - Layers the engine picked for caching ([RuntimeNode.layerBounds]) are drawn once into a GPU
 *   texture (a compositing layer) and then just moved, faded and scaled as a texture. They're
 *   re-rendered only when their content changes.
 * - Fading a node normally means drawing it into an offscreen buffer first. Nodes that draw
 *   with a single paint declare that they don't overlap, so alpha is applied directly.
 */
class SceneRenderer(private val text: TextLayouts, private val images: Images) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val srcRect = Rect()

    /** Re-records changed drawings and pushes changed transforms. */
    fun apply(runtime: SceneRuntime) {
        val transforms = runtime.transformChanged
        for (i in transforms.indices) applyTransform(transforms[i])
        val contents = runtime.contentChanged
        for (i in contents.indices) record(contents[i], runtime)
    }

    fun rootNode(runtime: SceneRuntime): RenderNode? = runtime.root.platform as? RenderNode

    fun release(runtime: SceneRuntime) {
        for (n in runtime.nodes) {
            (n.platform as? RenderNode)?.discardDisplayList()
            (n.platformLayer as? RenderNode)?.discardDisplayList()
            n.platform = null
            n.platformLayer = null
        }
    }

    private fun renderNode(n: RuntimeNode): RenderNode =
        n.platform as? RenderNode ?: RenderNode(n.id).also {
            it.setClipToBounds(false)
            n.platform = it
        }

    private fun applyTransform(n: RuntimeNode) {
        val r = renderNode(n)
        val w = ceil(n.width).toInt()
        val h = ceil(n.height).toInt()
        r.setPosition(0, 0, w, h)
        r.setTranslationX(n.drawX)
        r.setTranslationY(n.drawY)
        r.setAlpha(n.drawAlpha)
        val s = n.drawScale
        r.setScaleX(s)
        r.setScaleY(s)
        r.setRotationZ(n.drawRotation)
        r.setPivotX(w / 2f)
        r.setPivotY(h / 2f)
    }

    private fun record(n: RuntimeNode, runtime: SceneRuntime) {
        val r = renderNode(n)
        val w = max(1, ceil(n.width).toInt())
        val h = max(1, ceil(n.height).toInt())
        val bounds = n.layerBounds
        if (bounds == null) {
            (n.platformLayer as? RenderNode)?.discardDisplayList()
            n.platformLayer = null
            r.setHasOverlappingRendering(!n.drawsOnce)
            val canvas = r.beginRecording(w, h)
            try {
                drawContent(canvas, n, runtime)
            } finally {
                r.endRecording()
            }
        } else {
            // The content goes into its own node, rendered once into a GPU texture. Its bounds are
            // padded so glyphs and children that paint outside the box aren't clipped.
            val layer = n.platformLayer as? RenderNode ?: RenderNode("${n.id}#layer").also { n.platformLayer = it }
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            val right = ceil(bounds.right).toInt()
            val bottom = ceil(bounds.bottom).toInt()
            layer.setPosition(left, top, right, bottom)
            layer.setUseCompositingLayer(true, null)
            val lc = layer.beginRecording(right - left, bottom - top)
            try {
                lc.translate(-left.toFloat(), -top.toFloat())
                drawContent(lc, n, runtime)
            } finally {
                layer.endRecording()
            }
            // The outer node only places the texture, so alpha can go straight onto it.
            r.setHasOverlappingRendering(false)
            val canvas = r.beginRecording(w, h)
            try {
                canvas.drawRenderNode(layer)
            } finally {
                r.endRecording()
            }
        }
        n.recordCount++
    }

    private fun drawContent(canvas: Canvas, n: RuntimeNode, runtime: SceneRuntime) {
        when (val c = n.content) {
            ContentSpec.Group -> {
                if (n === runtime.root) runtime.background?.let { canvas.drawColor(it) }
                for (child in n.children) canvas.drawRenderNode(renderNode(child))
            }
            is ContentSpec.Text -> drawText(canvas, n)
            is ContentSpec.Shape -> drawShape(canvas, n, c)
            is ContentSpec.Image -> drawImage(canvas, n, c)
        }
    }

    private fun drawText(canvas: Canvas, n: RuntimeNode) {
        val layout = n.measureCache as? StaticLayout ?: return
        layout.paint.color = n.color
        layout.draw(canvas)
    }

    private fun drawShape(canvas: Canvas, n: RuntimeNode, c: ContentSpec.Shape) {
        n.fill?.let { color ->
            fillPaint.color = color
            rect.set(0f, 0f, n.width, n.height)
            drawShapePath(canvas, c.kind, n.radius, fillPaint)
        }
        n.stroke?.let { color ->
            if (n.strokeWidth <= 0f) return@let
            strokePaint.color = color
            strokePaint.strokeWidth = n.strokeWidth
            val inset = n.strokeWidth / 2f
            rect.set(inset, inset, n.width - inset, n.height - inset)
            drawShapePath(canvas, c.kind, max(0f, n.radius - inset), strokePaint)
        }
    }

    private fun drawShapePath(canvas: Canvas, kind: ShapeKind, radius: Float, paint: Paint) {
        when {
            kind == ShapeKind.OVAL -> canvas.drawOval(rect, paint)
            radius > 0f -> canvas.drawRoundRect(rect, radius, radius, paint)
            else -> canvas.drawRect(rect, paint)
        }
    }

    private fun drawImage(canvas: Canvas, n: RuntimeNode, c: ContentSpec.Image) {
        val bw = ceil(n.width).toInt()
        val bh = ceil(n.height).toInt()
        val bitmap = images.bitmap(c.src, bw, bh)
        if (bitmap == null) {
            // Missing image: a dim placeholder so the layout still shows where it would be.
            fillPaint.color = 0x33FFFFFF
            rect.set(0f, 0f, n.width, n.height)
            canvas.drawRect(rect, fillPaint)
            return
        }
        val iw = bitmap.width.toFloat()
        val ih = bitmap.height.toFloat()
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        when (c.fit) {
            Fit.FILL -> rect.set(0f, 0f, n.width, n.height)
            Fit.CONTAIN -> {
                val s = min(n.width / iw, n.height / ih)
                val dw = iw * s
                val dh = ih * s
                rect.set((n.width - dw) / 2f, (n.height - dh) / 2f, (n.width + dw) / 2f, (n.height + dh) / 2f)
            }
            Fit.COVER -> {
                val s = max(n.width / iw, n.height / ih)
                val dw = iw * s
                val dh = ih * s
                rect.set((n.width - dw) / 2f, (n.height - dh) / 2f, (n.width + dw) / 2f, (n.height + dh) / 2f)
                canvas.clipRect(0f, 0f, n.width, n.height)
            }
        }
        bitmapPaint.colorFilter = n.tint?.let { BlendModeColorFilter(it, BlendMode.SRC_IN) }
        canvas.drawBitmap(bitmap, srcRect, rect, bitmapPaint)
    }
}
