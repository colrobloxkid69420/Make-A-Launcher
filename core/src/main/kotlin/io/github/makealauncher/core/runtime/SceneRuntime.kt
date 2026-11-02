package io.github.makealauncher.core.runtime

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.expr.DataScope
import io.github.makealauncher.core.reactive.Effect
import io.github.makealauncher.core.reactive.Graph
import io.github.makealauncher.core.reactive.Source
import io.github.makealauncher.core.scene.AnimProperty
import io.github.makealauncher.core.scene.ContentSpec
import io.github.makealauncher.core.scene.Easing
import io.github.makealauncher.core.scene.GlobalType
import io.github.makealauncher.core.scene.NodeSpec
import io.github.makealauncher.core.scene.Preset
import io.github.makealauncher.core.scene.Value
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Runs a loaded preset. Each frame the host calls [update], then applies [contentChanged]
 * (re-record those drawings) and [transformChanged] (update position, alpha, scale, rotation).
 *
 * Work happens only when an input changes:
 * - a data change re-runs just the bindings that read it, and marks just their nodes;
 * - layout runs only when a size or offset changed;
 * - a scroll change touches only nodes that are paged or scroll-animated, and only their
 *   transforms. No binding runs during a scroll;
 * - layers on pages that are off screen sleep: their bindings stop listening (so, for
 *   example, a seconds display on another page doesn't keep a timer running), they aren't
 *   moved or drawn, and they catch up in the frame they come back into view;
 * - moving layers that are expensive to draw get a GPU layer ([RuntimeNode.layerBounds]), so
 *   scrolling composites a cached texture instead of redrawing them.
 */
class SceneRuntime(
    val preset: Preset,
    val graph: Graph,
    val data: DataSources,
    private val measurer: Measurer,
    /** Pixels per dp. */
    private val density: Float,
) {
    val globals: Map<String, Source<Any?>> = preset.globals.associate { g -> g.name to graph.source(g.value) }
    private val globalTypes: Map<String, GlobalType> = preset.globals.associate { it.name to it.type }
    private val scope = DataScope(data, globals)
    private val effects = ArrayList<Effect>()

    val root: RuntimeNode = RuntimeNode(null, "root", null, ContentSpec.Group)

    /** Every node in drawing order (parents before children), root first. */
    val nodes: List<RuntimeNode>
    private val byId = HashMap<String, RuntimeNode>()
    private val scrollNodes: Array<RuntimeNode>
    private val scrollAnims: Array<Array<CompiledAnim>>
    /** For each scroll node, its top-level ancestor (or itself), whose sleep state it shares. */
    private val scrollRoots: Array<RuntimeNode>
    private val pagedRoots: Array<RuntimeNode>
    /** Per paged root: the most it can move sideways (px) and grow (factor) from animations. */
    private val pagedReachX: FloatArray
    private val pagedMaxScale: FloatArray
    private val pagedRotates: BooleanArray
    /** Per paged root: the box its content paints in, relative to its top-left (set by layout). */
    private val pagedExtent: Array<FloatArray>

    /** Background colour of the root, or null. */
    var background: Int? = null; private set

    private var viewportWidth = 0f
    private var viewportHeight = 0f
    private var scroll = 0f
    private var scrollDirty = true
    private var layoutDirty = true
    private var pagingDirty = true
    private var layerPlanDirty = true
    private val pendingContent = LinkedHashSet<RuntimeNode>()
    private val pendingTransform = LinkedHashSet<RuntimeNode>()

    /** Pause bindings of paged layers while they're off screen. */
    var pauseOffscreen = true
        set(value) {
            if (field == value) return
            field = value
            pagingDirty = true
        }

    /** Give expensive moving layers a GPU layer. */
    var cacheLayers = true
        set(value) {
            if (field == value) return
            field = value
            layerPlanDirty = true
        }

    /** Filled by [update]: nodes whose drawing must be re-recorded this frame. */
    val contentChanged = ArrayList<RuntimeNode>()

    /** Filled by [update]: nodes whose transform properties must be updated this frame. */
    val transformChanged = ArrayList<RuntimeNode>()

    val stats = Stats()

    /** Values a binding produced that its property couldn't use, newest last (capped). */
    val runtimeErrors = ArrayList<String>()
    private val reportedErrors = HashSet<String>()

    val pages: Int get() = preset.scene.pages

    init {
        val all = ArrayList<RuntimeNode>()
        all += root
        for (layer in preset.scene.layers) build(layer, root, all)
        nodes = all
        scrollNodes = all.filter { n -> n.spec != null && (n.spec.page != null || n.spec.animations.isNotEmpty()) }.toTypedArray()
        for (n in scrollNodes) n.scrollDriven = true
        scrollRoots = Array(scrollNodes.size) { i ->
            var r = scrollNodes[i]
            while (r.parent != null && r.parent !== root) r = r.parent!!
            r
        }
        scrollAnims = Array(scrollNodes.size) { i ->
            scrollNodes[i].spec!!.animations.map { a ->
                val entries = a.tracks.entries.toList()
                CompiledAnim(
                    range = Animation.rangeFor(a, preset.scene.pages),
                    easing = a.easing,
                    props = Array(entries.size) { entries[it].key },
                    values = Array(entries.size) { entries[it].value },
                )
            }.toTypedArray()
        }
        pagedRoots = root.children.filter { it.spec!!.page != null }.toTypedArray()
        pagedReachX = FloatArray(pagedRoots.size) { i ->
            pagedRoots[i].spec!!.animations.sumOf { a ->
                (a.tracks[AnimProperty.TRANSLATE_X]?.maxOf { abs(it) } ?: 0f).toDouble()
            }.toFloat() * density
        }
        pagedMaxScale = FloatArray(pagedRoots.size) { i ->
            pagedRoots[i].spec!!.animations.fold(1f) { acc, a ->
                acc * max(1f, a.tracks[AnimProperty.SCALE]?.maxOf { abs(it) } ?: 1f)
            }
        }
        pagedRotates = BooleanArray(pagedRoots.size) { i ->
            pagedRoots[i].spec!!.animations.any { a -> a.tracks[AnimProperty.ROTATION]?.any { it != 0f } == true }
        }
        pagedExtent = Array(pagedRoots.size) { FloatArray(4) }
        preset.scene.background?.let { v -> bind(root, v, "background") { c -> if (background != c) { background = c; markContent(root) } } }
        for (n in all) {
            markContent(n)
            markTransform(n)
        }
    }

    // ---- host API ------------------------------------------------------------------------

    fun setViewport(widthPx: Float, heightPx: Float) {
        if (widthPx == viewportWidth && heightPx == viewportHeight) return
        viewportWidth = widthPx
        viewportHeight = heightPx
        layoutDirty = true
        scrollDirty = true
        pagingDirty = true
        layerPlanDirty = true
    }

    /** Home-screen scroll position in pages: 0 = first page, 1.5 = halfway to the third. */
    fun setScroll(pagePosition: Float) {
        if (pagePosition == scroll) return
        scroll = pagePosition
        scrollDirty = true
    }

    val scrollPosition: Float get() = scroll

    /** True when [update] has work to do. */
    val needsUpdate: Boolean
        get() = graph.hasPending || layoutDirty || scrollDirty || pagingDirty || layerPlanDirty ||
            pendingContent.isNotEmpty() || pendingTransform.isNotEmpty()

    /** Runs pending bindings, layout and scroll animations. Returns true if anything changed. */
    fun update(): Boolean {
        contentChanged.clear()
        transformChanged.clear()
        var ran = graph.flush()
        if (layoutDirty) {
            layoutDirty = false
            layoutPass()
        }
        if (scrollDirty) {
            scrollDirty = false
            applyScroll()
            pagingDirty = true
        }
        // A page that just came into view catches up before this frame is drawn.
        var passes = 0
        while (pagingDirty && passes < MAX_PAGING_PASSES) {
            pagingDirty = false
            passes++
            if (!updatePaging()) break
            ran += graph.flush()
            if (layoutDirty) {
                layoutDirty = false
                layoutPass()
            }
        }
        if (layerPlanDirty) {
            layerPlanDirty = false
            planLayers()
        }
        for (n in pendingContent) {
            n.contentDirty = false
            contentChanged += n
        }
        for (n in pendingTransform) {
            n.transformDirty = false
            transformChanged += n
        }
        pendingContent.clear()
        pendingTransform.clear()

        stats.updates++
        stats.bindingRuns += ran
        stats.contentUpdates += contentChanged.size
        stats.transformUpdates += transformChanged.size
        return contentChanged.isNotEmpty() || transformChanged.isNotEmpty()
    }

    fun node(id: String): RuntimeNode? = byId[id]

    /** Changes a global (the theme panel will call this). */
    fun setGlobal(name: String, value: Any?) {
        globals[name]?.set(value)
    }

    fun globalType(name: String): GlobalType? = globalTypes[name]

    fun dispose() {
        for (e in effects) e.dispose()
        effects.clear()
    }

    class Stats {
        var updates = 0L
        var bindingRuns = 0L
        var contentUpdates = 0L
        var transformUpdates = 0L
        var layouts = 0L
        var measures = 0L
        var layerPlans = 0L
    }

    // ---- building and binding ------------------------------------------------------------

    private fun build(spec: NodeSpec, parent: RuntimeNode, out: MutableList<RuntimeNode>) {
        val n = RuntimeNode(spec, spec.id, parent, spec.content)
        parent.children += n
        out += n
        byId[spec.id] = n

        bind(n, spec.offsetX, "offset") { v -> val px = v * density; if (n.offsetX != px) { n.offsetX = px; layoutDirty = true } }
        bind(n, spec.offsetY, "offset") { v -> val px = v * density; if (n.offsetY != px) { n.offsetY = px; layoutDirty = true } }
        spec.width?.let { w ->
            bind(n, w, "width") { v -> val px = v * density; if (n.specWidth != px) { n.specWidth = px; n.needsMeasure = true; layoutDirty = true } }
        }
        spec.height?.let { h ->
            bind(n, h, "height") { v -> val px = v * density; if (n.specHeight != px) { n.specHeight = px; layoutDirty = true } }
        }
        bind(n, spec.alpha, "alpha") { v -> if (n.alpha != v) { n.alpha = v; markTransform(n) } }
        bind(n, spec.rotation, "rotation") { v -> if (n.rotation != v) { n.rotation = v; markTransform(n) } }
        bind(n, spec.scale, "scale") { v -> if (n.scale != v) { n.scale = v; markTransform(n) } }
        bind(n, spec.visible, "visible") { v -> if (n.visible != v) { n.visible = v; markTransform(n) } }

        when (val c = spec.content) {
            is ContentSpec.Text -> {
                bind(n, c.text, "text") { v -> if (n.text != v) { n.text = v; textChanged(n) } }
                bind(n, c.size, "size") { v -> val px = v * density; if (n.textSize != px) { n.textSize = px; textChanged(n) } }
                bind(n, c.color, "color") { v -> if (n.color != v) { n.color = v; markContent(n) } }
            }
            is ContentSpec.Shape -> {
                bind(n, c.radius, "radius") { v -> val px = v * density; if (n.radius != px) { n.radius = px; markContent(n) } }
                c.fill?.let { f -> bind(n, f, "fill") { v -> if (n.fill != v) { n.fill = v; markContent(n) } } }
                c.stroke?.let { s -> bind(n, s, "stroke") { v -> if (n.stroke != v) { n.stroke = v; markContent(n) } } }
                bind(n, c.strokeWidth, "stroke_width") { v -> val px = v * density; if (n.strokeWidth != px) { n.strokeWidth = px; markContent(n) } }
            }
            is ContentSpec.Image -> {
                c.tint?.let { t -> bind(n, t, "tint") { v -> if (n.tint != v) { n.tint = v; markContent(n) } } }
            }
            ContentSpec.Group -> Unit
        }

        for (child in spec.children) build(child, n, out)
    }

    private fun textChanged(n: RuntimeNode) {
        n.needsMeasure = true
        layoutDirty = true
        markContent(n)
    }

    private fun <T> bind(n: RuntimeNode, value: Value<T>, property: String, apply: (T) -> Unit) {
        when (value) {
            is Value.Const -> apply(value.value)
            is Value.Bound -> {
                val e = graph.effect {
                    n.bindingRuns++
                    val raw = value.template.evaluate(scope)
                    val converted = value.convert(raw)
                    if (converted != null) {
                        apply(converted)
                    } else {
                        reportError("line ${value.line}: '${n.id}' $property got '${raw}', which isn't a valid value")
                    }
                }
                effects += e
                n.effects += e
            }
        }
    }

    private fun reportError(message: String) {
        if (!reportedErrors.add(message)) return
        runtimeErrors += message
        if (runtimeErrors.size > MAX_ERRORS) runtimeErrors.removeAt(0)
    }

    private fun markContent(n: RuntimeNode) {
        if (!n.contentDirty) {
            n.contentDirty = true
            pendingContent += n
        }
    }

    private fun markTransform(n: RuntimeNode) {
        if (!n.transformDirty) {
            n.transformDirty = true
            pendingTransform += n
        }
        // A layer's bounds include its children's rotation and scale, so re-plan if they change.
        if (n.parent != null && n.parent !== root && hasLayeredAncestor(n)) layerPlanDirty = true
    }

    private fun hasLayeredAncestor(n: RuntimeNode): Boolean {
        var p = n.parent
        while (p != null) {
            if (p.layerBounds != null) return true
            p = p.parent
        }
        return false
    }

    // ---- layout --------------------------------------------------------------------------

    private fun layoutPass() {
        stats.layouts++
        if (root.width != viewportWidth || root.height != viewportHeight) {
            root.width = viewportWidth
            root.height = viewportHeight
            markContent(root)
            markTransform(root)
        }
        for (child in root.children) layout(child, root.width, root.height)
        for (i in pagedRoots.indices) {
            val n = pagedRoots[i]
            val b = pagedExtent[i]
            b[0] = 0f
            b[1] = 0f
            b[2] = n.width
            b[3] = n.height
            expandForContent(n, 0f, 0f, b)
            for (c in n.children) unionDescendant(c, 0f, 0f, b)
        }
        pagingDirty = true
        layerPlanDirty = true
    }

    private fun layout(n: RuntimeNode, parentW: Float, parentH: Float) {
        var w: Float
        var h: Float
        when (n.content) {
            is ContentSpec.Text -> {
                if (n.needsMeasure) {
                    val size = measurer.measureText(n, n.specWidth.takeUnless { it.isNaN() })
                    n.measuredWidth = size.width
                    n.measuredHeight = size.height
                    n.needsMeasure = false
                    stats.measures++
                }
                w = if (n.specWidth.isNaN()) n.measuredWidth else n.specWidth
                h = if (n.specHeight.isNaN()) n.measuredHeight else n.specHeight
            }
            is ContentSpec.Image -> {
                if (n.needsMeasure && (n.specWidth.isNaN() || n.specHeight.isNaN())) {
                    val size = measurer.imageSize(n)
                    n.measuredWidth = size?.width ?: (DEFAULT_SIZE_DP * density)
                    n.measuredHeight = size?.height ?: (DEFAULT_SIZE_DP * density)
                    stats.measures++
                }
                n.needsMeasure = false
                w = if (n.specWidth.isNaN()) n.measuredWidth else n.specWidth
                h = if (n.specHeight.isNaN()) n.measuredHeight else n.specHeight
            }
            is ContentSpec.Shape -> {
                w = if (n.specWidth.isNaN()) DEFAULT_SIZE_DP * density else n.specWidth
                h = if (n.specHeight.isNaN()) DEFAULT_SIZE_DP * density else n.specHeight
            }
            ContentSpec.Group -> {
                w = if (n.specWidth.isNaN()) parentW else n.specWidth
                h = if (n.specHeight.isNaN()) parentH else n.specHeight
            }
        }
        val anchor = n.spec!!.anchor
        val left = parentW * anchor.fx - w * anchor.fx + n.offsetX
        val top = parentH * anchor.fy - h * anchor.fy + n.offsetY
        if (w != n.width || h != n.height) {
            n.width = w
            n.height = h
            markContent(n)
            markTransform(n)
        }
        if (left != n.left || top != n.top) {
            n.left = left
            n.top = top
            markTransform(n)
        }
        for (child in n.children) layout(child, w, h)
    }

    // ---- scrolling -----------------------------------------------------------------------

    /** Runs every frame while scrolling, so it loops over arrays and allocates nothing. */
    private fun applyScroll() {
        for (i in scrollNodes.indices) {
            val n = scrollNodes[i]
            val page = n.spec!!.page
            val shift = if (page != null) (page - scroll) * viewportWidth else 0f
            var tx = 0f
            var ty = 0f
            var a = 1f
            var s = 1f
            var r = 0f
            val anims = scrollAnims[i]
            for (j in anims.indices) {
                val anim = anims[j]
                val t = Animation.progress(scroll, anim.range, anim.easing)
                for (k in anim.props.indices) {
                    val v = Animation.sample(anim.values[k], t)
                    when (anim.props[k]) {
                        AnimProperty.TRANSLATE_X -> tx += v * density
                        AnimProperty.TRANSLATE_Y -> ty += v * density
                        AnimProperty.ALPHA -> a *= v
                        AnimProperty.SCALE -> s *= v
                        AnimProperty.ROTATION -> r += v
                    }
                }
            }
            if (shift != n.pageShift || tx != n.animTx || ty != n.animTy || a != n.animAlpha || s != n.animScale || r != n.animRotation) {
                n.pageShift = shift
                n.animTx = tx
                n.animTy = ty
                n.animAlpha = a
                n.animScale = s
                n.animRotation = r
                // Values stay current while asleep, but the renderer only hears about awake layers.
                if (!scrollRoots[i].paused) markTransform(n)
            }
        }
    }

    private class CompiledAnim(
        val range: ClosedFloatingPointRange<Float>,
        val easing: Easing,
        val props: Array<AnimProperty>,
        val values: Array<FloatArray>,
    )

    // ---- paging --------------------------------------------------------------------------

    /**
     * Puts paged layers that are fully off screen to sleep and wakes those that came back.
     * Visibility uses a circle around the layer that covers any rotation, scaling and sideways
     * animation, so a layer never sleeps while any part of it could be showing.
     *
     * A sleeping layer's bindings are paused, it gets alpha 0 (Android skips drawing it and
     * everything inside it), and its scroll transforms aren't sent to the renderer.
     * Returns true if anything woke up.
     */
    private fun updatePaging(): Boolean {
        var woke = false
        for (i in pagedRoots.indices) {
            val n = pagedRoots[i]
            val off = pauseOffscreen && viewportWidth > 0f && isOffscreen(n, i)
            if (off == n.paused) continue
            n.paused = off
            setPausedRecursive(n, off)
            markTransform(n)
            if (!off) {
                // Push the transforms it skipped while asleep.
                markScrollDrivenRecursive(n)
                woke = true
            }
        }
        return woke
    }

    private fun markScrollDrivenRecursive(n: RuntimeNode) {
        if (n.scrollDriven) markTransform(n)
        for (c in n.children) markScrollDrivenRecursive(c)
    }

    /**
     * True when nothing the layer paints can be on screen: its content box (including children
     * and text overhang), grown by its largest scale, swept by any rotation, and widened by
     * how far its animations can move it sideways.
     */
    private fun isOffscreen(n: RuntimeNode, i: Int): Boolean {
        val b = pagedExtent[i]
        val scale = max(1f, abs(n.scale)) * pagedMaxScale[i]
        val cx = n.width / 2f
        val lo: Float
        val hi: Float
        if (pagedRotates[i] || n.rotation != 0f) {
            val cy = n.height / 2f
            val r = hypot(max(abs(b[0] - cx), abs(b[2] - cx)), max(abs(b[1] - cy), abs(b[3] - cy))) * scale
            lo = cx - r
            hi = cx + r
        } else {
            lo = cx + (b[0] - cx) * scale
            hi = cx + (b[2] - cx) * scale
        }
        val x = n.left + n.pageShift
        val reach = pagedReachX[i]
        return x + hi + reach <= 0f || x + lo - reach >= viewportWidth
    }

    private fun setPausedRecursive(n: RuntimeNode, pause: Boolean) {
        for (e in n.effects) if (pause) e.pause() else e.resume()
        for (c in n.children) setPausedRecursive(c, pause)
    }

    // ---- GPU layers ----------------------------------------------------------------------

    /**
     * Picks the moving layers worth caching on the GPU: groups and large or long text that move
     * as one piece. Shapes and images are single cheap draws, and a layer whose children move
     * on their own would have to be re-rendered every frame, so neither gets one. Total layer
     * memory is capped at [LAYER_BUDGET_SCREENS] screens.
     */
    private fun planLayers() {
        stats.layerPlans++
        val screen = viewportWidth * viewportHeight
        var used = 0f
        for (n in scrollNodes) {
            val wanted = if (cacheLayers && screen > 0f && wantsLayer(n)) layerBoundsFor(n) else null
            val chosen = if (wanted != null && wanted.area <= screen && used + wanted.area <= screen * LAYER_BUDGET_SCREENS) {
                used += wanted.area
                wanted
            } else {
                null
            }
            if (chosen != n.layerBounds) {
                n.layerBounds = chosen
                markContent(n)
            }
        }
    }

    private fun wantsLayer(n: RuntimeNode): Boolean {
        if (n.width <= 0f || n.height <= 0f) return false
        if (anyDescendant(n) { it.scrollDriven }) return false
        return when (n.content) {
            ContentSpec.Group -> countDescendants(n) >= 2
            is ContentSpec.Text -> n.textSize >= LARGE_TEXT_DP * density || n.text.length > LONG_TEXT_CHARS
            else -> false
        }
    }

    /** The node's box plus everything its content can paint outside it, relative to its top-left. */
    private fun layerBoundsFor(n: RuntimeNode): LayerBounds {
        val b = floatArrayOf(0f, 0f, n.width, n.height)
        expandForContent(n, 0f, 0f, b)
        for (c in n.children) unionDescendant(c, 0f, 0f, b)
        val pad = LAYER_PAD_DP * density
        return LayerBounds(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
    }

    private fun unionDescendant(n: RuntimeNode, ox: Float, oy: Float, b: FloatArray) {
        val x = ox + n.left
        val y = oy + n.top
        val s = abs(n.scale)
        val rad = Math.toRadians(n.rotation.toDouble())
        // Bounding box of the node's rectangle after rotation and scale about its centre.
        val w = ((abs(cos(rad)) * n.width + abs(sin(rad)) * n.height) * s).toFloat()
        val h = ((abs(sin(rad)) * n.width + abs(cos(rad)) * n.height) * s).toFloat()
        val cx = x + n.width / 2f
        val cy = y + n.height / 2f
        b[0] = min(b[0], cx - w / 2f)
        b[1] = min(b[1], cy - h / 2f)
        b[2] = max(b[2], cx + w / 2f)
        b[3] = max(b[3], cy + h / 2f)
        expandForContent(n, x, y, b)
        for (c in n.children) unionDescendant(c, x, y, b)
    }

    /** Text glyphs can paint a little outside their layout box. */
    private fun expandForContent(n: RuntimeNode, x: Float, y: Float, b: FloatArray) {
        if (n.content !is ContentSpec.Text) return
        val pad = n.textSize * TEXT_OVERHANG
        b[0] = min(b[0], x - pad)
        b[1] = min(b[1], y - pad)
        b[2] = max(b[2], x + n.width + pad)
        b[3] = max(b[3], y + n.height + pad)
    }

    private fun anyDescendant(n: RuntimeNode, test: (RuntimeNode) -> Boolean): Boolean {
        for (c in n.children) if (test(c) || anyDescendant(c, test)) return true
        return false
    }

    private fun countDescendants(n: RuntimeNode): Int = n.children.sumOf { 1 + countDescendants(it) }

    private companion object {
        const val DEFAULT_SIZE_DP = 100f
        const val MAX_ERRORS = 50
        const val LARGE_TEXT_DP = 48f
        const val LONG_TEXT_CHARS = 40
        const val LAYER_PAD_DP = 2f
        const val TEXT_OVERHANG = 0.25f
        const val LAYER_BUDGET_SCREENS = 2f
        const val MAX_PAGING_PASSES = 3
    }
}
