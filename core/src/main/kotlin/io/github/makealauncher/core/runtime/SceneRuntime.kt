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
 * Runs a loaded preset. Each frame the host calls [update], then applies the changes it lists:
 * [contentChanged] (re-record those drawings), [transformChanged] (update position, alpha,
 * scale, rotation), [runsChanged] (re-record a page run's structure) and [runsMoved] (update a
 * page run's position and visibility).
 *
 * Work happens only when an input changes:
 * - a data change re-runs just the bindings that read it, and marks just their nodes;
 * - layout re-runs only for nodes whose size, offset or text changed, and their children;
 * - consecutive layers on the same page form a [PageRun] that moves as one piece, so scrolling
 *   updates one value per visible page plus the layers that have their own scroll animation.
 *   No binding runs during a scroll;
 * - a run's layers that don't animate are cached together as one GPU layer ([SubRun]), so
 *   scrolling composites cached images instead of redrawing every layer;
 * - off-screen layers pause their bindings (so, for example, a seconds display on another page
 *   doesn't keep a timer running) and catch up in the frame they come back into view; a run
 *   with nothing on screen sleeps and isn't drawn or moved;
 * - other moving layers that are expensive to draw get their own GPU layer
 *   ([RuntimeNode.layerBounds]).
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

    /** What the root draws, in order: top-level [RuntimeNode]s that aren't on a page, and [PageRun]s. */
    val rootEntries: List<Any>
    val runs: List<PageRun>

    private val byId = HashMap<String, RuntimeNode>()
    /** Paged layers and scroll-animated nodes: everything whose values depend on the scroll. */
    private val scrollNodes: Array<RuntimeNode>
    private val scrollAnims: Array<Array<CompiledAnim>>
    private val animatedNodes: Array<RuntimeNode>
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
    private var fullLayout = true
    private var pagingDirty = true
    private var layerPlanDirty = true
    private val layoutQueue = ArrayList<RuntimeNode>()
    private val pendingContent = LinkedHashSet<RuntimeNode>()
    private val pendingTransform = LinkedHashSet<RuntimeNode>()
    private val pendingRunMoves = LinkedHashSet<PageRun>()
    private val pendingRunChanges = LinkedHashSet<PageRun>()

    /** Pause and hide paged layers while they're off screen. */
    var pauseOffscreen = true
        set(value) {
            if (field == value) return
            field = value
            pagingDirty = true
        }

    /** Cache static page content and expensive moving layers in GPU layers. */
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

    /** Filled by [update]: runs whose structure (parts, cached-layer bounds) must be re-recorded. */
    val runsChanged = ArrayList<PageRun>()

    /** Filled by [update]: runs whose position or visibility changed. */
    val runsMoved = ArrayList<PageRun>()

    val stats = Stats()

    /** Values a binding produced that its property couldn't use, newest last (capped). */
    val runtimeErrors = ArrayList<String>()
    private val reportedErrors = HashSet<String>()

    val pages: Int get() = preset.scene.pages

    init {
        val all = ArrayList<RuntimeNode>()
        all += root
        root.topLevel = root
        for (layer in preset.scene.layers) build(layer, root, root, all)
        nodes = all

        for (n in all) if (n.spec != null && n.spec.animations.isNotEmpty()) n.scrollDriven = true
        animatedNodes = all.filter { it.scrollDriven }.toTypedArray()
        scrollNodes = all.filter { n -> n.spec != null && (n.spec.page != null || n.scrollDriven) }.toTypedArray()
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

        // Group consecutive top-level layers on the same page into runs, keeping drawing order.
        val entries = ArrayList<Any>()
        val runList = ArrayList<PageRun>()
        var current: PageRun? = null
        for (n in root.children) {
            val page = n.spec!!.page
            if (page == null) {
                current = null
                entries += n
                continue
            }
            if (current == null || current.page != page) {
                current = PageRun(page)
                runList += current
                entries += current
            }
            current.members += n
            n.run = current
            if (n.scrollDriven || anyDescendant(n) { it.scrollDriven }) {
                current.parts += n
            } else {
                val last = current.parts.lastOrNull()
                val sub = last as? SubRun ?: SubRun(current).also { current.parts += it }
                sub.nodes += n
                n.subRun = sub
            }
        }
        rootEntries = entries
        runs = runList

        pagedRoots = root.children.filter { it.spec!!.page != null }.toTypedArray()
        for (i in pagedRoots.indices) pagedRoots[i].pagedIndex = i
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
        for (r in runs) {
            markRunChanged(r)
            markRunMoved(r)
        }
    }

    // ---- host API ------------------------------------------------------------------------

    fun setViewport(widthPx: Float, heightPx: Float) {
        if (widthPx == viewportWidth && heightPx == viewportHeight) return
        viewportWidth = widthPx
        viewportHeight = heightPx
        fullLayout = true
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
            pendingContent.isNotEmpty() || pendingTransform.isNotEmpty() ||
            pendingRunMoves.isNotEmpty() || pendingRunChanges.isNotEmpty()

    /** Runs pending bindings, layout and scroll animations. Returns true if anything changed. */
    fun update(): Boolean {
        contentChanged.clear()
        transformChanged.clear()
        runsChanged.clear()
        runsMoved.clear()
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
            planSubRuns()
        }
        for (n in pendingContent) {
            n.contentDirty = false
            contentChanged += n
        }
        for (n in pendingTransform) {
            n.transformDirty = false
            transformChanged += n
        }
        for (r in pendingRunChanges) {
            r.changed = false
            runsChanged += r
        }
        for (r in pendingRunMoves) {
            r.moved = false
            runsMoved += r
        }
        pendingContent.clear()
        pendingTransform.clear()
        pendingRunChanges.clear()
        pendingRunMoves.clear()

        stats.updates++
        stats.bindingRuns += ran
        stats.contentUpdates += contentChanged.size
        stats.transformUpdates += transformChanged.size + runsMoved.size
        return contentChanged.isNotEmpty() || transformChanged.isNotEmpty() || runsChanged.isNotEmpty() || runsMoved.isNotEmpty()
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
        /** Node and page-run transform updates. */
        var transformUpdates = 0L
        var layouts = 0L
        /** Nodes laid out, across all layout passes. */
        var nodesLaidOut = 0L
        var measures = 0L
        var layerPlans = 0L
    }

    // ---- building and binding ------------------------------------------------------------

    private fun build(spec: NodeSpec, parent: RuntimeNode, top: RuntimeNode, out: MutableList<RuntimeNode>) {
        val n = RuntimeNode(spec, spec.id, parent, spec.content)
        n.topLevel = if (parent === root) n else top
        parent.children += n
        out += n
        byId[spec.id] = n

        bind(n, spec.offsetX, "offset") { v -> val px = v * density; if (n.offsetX != px) { n.offsetX = px; requestLayout(n) } }
        bind(n, spec.offsetY, "offset") { v -> val px = v * density; if (n.offsetY != px) { n.offsetY = px; requestLayout(n) } }
        spec.width?.let { w ->
            bind(n, w, "width") { v -> val px = v * density; if (n.specWidth != px) { n.specWidth = px; n.needsMeasure = true; requestLayout(n) } }
        }
        spec.height?.let { h ->
            bind(n, h, "height") { v -> val px = v * density; if (n.specHeight != px) { n.specHeight = px; requestLayout(n) } }
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

        for (child in spec.children) build(child, n, n.topLevel!!, out)
    }

    private fun textChanged(n: RuntimeNode) {
        n.needsMeasure = true
        requestLayout(n)
        markContent(n)
    }

    private fun requestLayout(n: RuntimeNode) {
        layoutDirty = true
        if (!n.layoutQueued) {
            n.layoutQueued = true
            layoutQueue += n
        }
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
        // A cached layer's bounds include its contents' rotation and scale, so re-plan if they change.
        val t = n.topLevel
        if (t != null && t !== root) {
            if (t.subRun != null && !n.scrollDriven) {
                t.run?.boundsDirty = true
                layerPlanDirty = true
            }
            if (n !== t && hasLayeredAncestor(n)) layerPlanDirty = true
        }
    }

    private fun markRunMoved(r: PageRun) {
        if (!r.moved) {
            r.moved = true
            pendingRunMoves += r
        }
    }

    private fun markRunChanged(r: PageRun) {
        if (!r.changed) {
            r.changed = true
            pendingRunChanges += r
        }
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

    /**
     * Lays out what changed. A node's position depends only on its parent's size and its own
     * size, offset and anchor, so a changed node is re-laid out on its own, and its children
     * only if its size changed. A new viewport size lays out everything.
     */
    private fun layoutPass() {
        stats.layouts++
        val touched = touchedTops
        touched.clear()
        if (fullLayout) {
            fullLayout = false
            for (n in layoutQueue) n.layoutQueued = false
            layoutQueue.clear()
            if (root.width != viewportWidth || root.height != viewportHeight) {
                root.width = viewportWidth
                root.height = viewportHeight
                markContent(root)
                markTransform(root)
            }
            for (child in root.children) {
                layout(child, root.width, root.height, all = true)
                touched += child
            }
        } else {
            // Bindings can queue more layout while this runs (they don't, but stay safe).
            var i = 0
            while (i < layoutQueue.size) {
                val n = layoutQueue[i++]
                n.layoutQueued = false
                val p = n.parent ?: continue
                layout(n, p.width, p.height, all = false)
                n.topLevel?.let { touched += it }
            }
            layoutQueue.clear()
        }
        for (t in touched) {
            val i = t.pagedIndex
            if (i < 0) continue
            val b = pagedExtent[i]
            b[0] = 0f
            b[1] = 0f
            b[2] = t.width
            b[3] = t.height
            expandForContent(t, 0f, 0f, b)
            for (c in t.children) unionDescendant(c, 0f, 0f, b)
            t.run?.boundsDirty = true
        }
        pagingDirty = true
        layerPlanDirty = true
    }

    private val touchedTops = LinkedHashSet<RuntimeNode>()

    private fun layout(n: RuntimeNode, parentW: Float, parentH: Float, all: Boolean) {
        stats.nodesLaidOut++
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
        val resized = w != n.width || h != n.height
        if (resized) {
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
        if (all || resized) for (child in n.children) layout(child, w, h, all)
    }

    // ---- scrolling -----------------------------------------------------------------------

    /** Runs every frame while scrolling, so it loops over arrays and allocates nothing. */
    private fun applyScroll() {
        for (i in runs.indices) {
            val r = runs[i]
            val shift = (r.page - scroll) * viewportWidth
            if (shift != r.shift) {
                r.shift = shift
                if (r.awake) markRunMoved(r)
            }
        }
        for (i in scrollNodes.indices) {
            val n = scrollNodes[i]
            val page = n.spec!!.page
            // Kept per layer for the off-screen check; the run applies it when drawing.
            n.pageShift = if (page != null) (page - scroll) * viewportWidth else 0f
            if (!n.scrollDriven) continue
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
            if (tx != n.animTx || ty != n.animTy || a != n.animAlpha || s != n.animScale || r != n.animRotation) {
                n.animTx = tx
                n.animTy = ty
                n.animAlpha = a
                n.animScale = s
                n.animRotation = r
                // Values stay current while asleep, but the renderer only hears about awake runs.
                if (n.topLevel?.run?.awake != false) markTransform(n)
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
     * Pauses the bindings of paged layers that are fully off screen and resumes those that came
     * back; then puts runs with nothing on screen to sleep (not drawn or moved) and wakes the
     * others. Returns true if any bindings resumed.
     */
    private fun updatePaging(): Boolean {
        var resumed = false
        for (i in pagedRoots.indices) {
            val n = pagedRoots[i]
            val off = pauseOffscreen && viewportWidth > 0f && isOffscreen(n, i)
            if (off == n.paused) continue
            n.paused = off
            setPausedRecursive(n, off)
            if (!off) resumed = true
        }
        for (r in runs) {
            val awake = !pauseOffscreen || r.members.any { !it.paused }
            if (awake == r.awake) continue
            r.awake = awake
            markRunMoved(r)
            layerPlanDirty = true
            if (awake) {
                // Push the animation transforms it skipped while asleep.
                for (m in r.members) markScrollDrivenRecursive(m)
            }
        }
        return resumed
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
     * Picks the scroll-animated layers worth caching on the GPU: groups and large or long text
     * that move as one piece. Shapes and images are single cheap draws, and a layer whose
     * children move on their own would have to be re-rendered every frame, so neither gets one.
     * Memory is capped at [NODE_LAYER_BUDGET_SCREENS] screens.
     */
    private fun planLayers() {
        stats.layerPlans++
        val screen = viewportWidth * viewportHeight
        var used = 0f
        for (n in animatedNodes) {
            val wanted = if (cacheLayers && screen > 0f && wantsLayer(n)) layerBoundsFor(n) else null
            val chosen = if (wanted != null && wanted.area <= screen && used + wanted.area <= screen * NODE_LAYER_BUDGET_SCREENS) {
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

    /**
     * Caches the static stretches of awake runs as GPU layers, at most
     * [SUBRUN_BUDGET_SCREENS] screens of them. A stretch that's a single simple layer isn't
     * worth a layer of its own.
     */
    private fun planSubRuns() {
        val screen = viewportWidth * viewportHeight
        var used = 0f
        for (r in runs) {
            val recompute = r.boundsDirty
            r.boundsDirty = false
            for (part in r.parts) {
                val sub = part as? SubRun ?: continue
                if (recompute) {
                    val b = subRunBounds(sub)
                    if (b != sub.bounds) {
                        sub.bounds = b
                        markRunChanged(r)
                    }
                }
                val bounds = sub.bounds
                val want = cacheLayers && r.awake && screen > 0f && bounds != null &&
                    sub.nodes.sumOf { 1 + countDescendants(it) } >= 2
                val chosen = want && bounds!!.area <= screen && used + bounds.area <= screen * SUBRUN_BUDGET_SCREENS
                if (chosen) used += bounds!!.area
                if (chosen != sub.layered) {
                    sub.layered = chosen
                    markRunChanged(r)
                }
            }
        }
    }

    /** Everything the stretch's layers paint, in page coordinates, padded a little. */
    private fun subRunBounds(sub: SubRun): LayerBounds? {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (n in sub.nodes) unionDescendant(n, 0f, 0f, b)
        if (b[0] > b[2] || b[1] > b[3]) return null
        val pad = LAYER_PAD_DP * density
        return LayerBounds(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
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
        val w: Float
        val h: Float
        if (n.rotation == 0f) {
            w = n.width * s
            h = n.height * s
        } else {
            // Bounding box of the node's rectangle after rotation and scale about its centre.
            val rad = Math.toRadians(n.rotation.toDouble())
            w = ((abs(cos(rad)) * n.width + abs(sin(rad)) * n.height) * s).toFloat()
            h = ((abs(sin(rad)) * n.width + abs(cos(rad)) * n.height) * s).toFloat()
        }
        val cx = x + n.width / 2f
        val cy = y + n.height / 2f
        b[0] = min(b[0], cx - w / 2f)
        b[1] = min(b[1], cy - h / 2f)
        b[2] = max(b[2], cx + w / 2f)
        b[3] = max(b[3], cy + h / 2f)
        expandForContent(n, x, y, b)
        for (c in n.children) unionDescendant(c, x, y, b)
    }

    /**
     * Text glyphs can paint a little outside their layout box. The allowance is generous (a
     * quarter of the text size) so cached layers never clip a glyph; the cost is that text right
     * at a page's edge keeps the neighbouring page awake.
     */
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
        const val NODE_LAYER_BUDGET_SCREENS = 1f
        const val SUBRUN_BUDGET_SCREENS = 2.5f
        const val MAX_PAGING_PASSES = 3
    }
}
