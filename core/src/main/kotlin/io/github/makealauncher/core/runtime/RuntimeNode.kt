package io.github.makealauncher.core.runtime

import io.github.makealauncher.core.scene.ContentSpec
import io.github.makealauncher.core.scene.NodeSpec

/**
 * The live state of one component: resolved property values in pixels, its layout box,
 * the current animation offsets, and two dirty flags the renderer acts on:
 *
 * - content dirty: the drawing itself changed (text, colour, size), so re-record it.
 * - transform dirty: only position, alpha, scale or rotation changed, so update properties.
 *
 * Scrolling only ever produces transform changes.
 */
class RuntimeNode internal constructor(
    /** Null for the scene root. */
    val spec: NodeSpec?,
    val id: String,
    val parent: RuntimeNode?,
    val content: ContentSpec,
) {
    val children = ArrayList<RuntimeNode>()

    // ---- resolved properties (px) ---------------------------------------------------------
    var offsetX = 0f; internal set
    var offsetY = 0f; internal set
    /** Explicit size, or NaN when sized automatically. */
    var specWidth = Float.NaN; internal set
    var specHeight = Float.NaN; internal set
    var alpha = 1f; internal set
    var rotation = 0f; internal set
    var scale = 1f; internal set
    var visible = true; internal set

    // text
    var text = ""; internal set
    var textSize = 16f; internal set
    var color = 0xFFFFFFFF.toInt(); internal set

    // shape
    var radius = 0f; internal set
    var fill: Int? = null; internal set
    var stroke: Int? = null; internal set
    var strokeWidth = 0f; internal set

    // image
    var tint: Int? = null; internal set

    // ---- layout result (px, relative to the parent's top-left) ---------------------------
    var left = 0f; internal set
    var top = 0f; internal set
    var width = 0f; internal set
    var height = 0f; internal set

    // ---- animation and paging result -----------------------------------------------------
    var pageShift = 0f; internal set
    var animTx = 0f; internal set
    var animTy = 0f; internal set
    var animAlpha = 1f; internal set
    var animScale = 1f; internal set
    var animRotation = 0f; internal set

    /** Final values the renderer applies as RenderNode properties. */
    val drawX: Float get() = left + pageShift + animTx
    val drawY: Float get() = top + animTy
    val drawAlpha: Float get() = if (visible) (alpha * animAlpha).coerceIn(0f, 1f) else 0f
    val drawScale: Float get() = scale * animScale
    val drawRotation: Float get() = rotation + animRotation

    // ---- bookkeeping ---------------------------------------------------------------------
    internal var needsMeasure = true
    internal var measuredWidth = 0f
    internal var measuredHeight = 0f
    internal var contentDirty = false
    internal var transformDirty = false

    /** Bindings of this node; paused together when its page is off screen. */
    internal val effects = ArrayList<io.github.makealauncher.core.reactive.Effect>()

    /** True for a paged layer that's off screen: its bindings are paused. */
    var paused = false; internal set

    /** Moves on every scroll frame (paged or scroll-animated). */
    var scrollDriven = false; internal set

    /**
     * When set, the renderer caches this node's drawing in a GPU layer covering these bounds
     * (relative to the node's top-left, in px), so moving it is just compositing a texture.
     */
    var layerBounds: LayerBounds? = null; internal set

    /**
     * True when the node draws with a single paint, so alpha can be applied directly instead
     * of through an offscreen buffer.
     */
    val drawsOnce: Boolean
        get() = when (content) {
            is ContentSpec.Text, is ContentSpec.Image -> true
            is ContentSpec.Shape -> fill == null || stroke == null || strokeWidth <= 0f
            ContentSpec.Group -> false
        }

    /** Platform render object (a RenderNode on Android). */
    var platform: Any? = null

    /** Platform object for the cached layer, when [layerBounds] is set. */
    var platformLayer: Any? = null

    /** Platform cache filled while measuring and reused for drawing (e.g. a StaticLayout). */
    var measureCache: Any? = null

    /** How many times this node's drawing was re-recorded; shown by the inspector. */
    var recordCount = 0
    /** How many times one of this node's bindings re-evaluated. */
    var bindingRuns = 0; internal set

    override fun toString() = "RuntimeNode($id)"
}

class Size(val width: Float, val height: Float)

data class LayerBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left) * (bottom - top)
}

/** Platform measurements the layout pass needs. Results are in pixels. */
interface Measurer {
    /** Measures [node]'s text; [maxWidth] is the wrap width, or null for natural width. */
    fun measureText(node: RuntimeNode, maxWidth: Float?): Size

    /** The image's intrinsic size, or null if it can't be loaded. */
    fun imageSize(node: RuntimeNode): Size?
}
