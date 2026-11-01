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

/**
 * Runs a loaded preset. Each frame the host calls [update], then applies [contentChanged]
 * (re-record those drawings) and [transformChanged] (update position, alpha, scale, rotation).
 *
 * Work happens only when an input changes:
 * - a data change re-runs just the bindings that read it, and marks just their nodes;
 * - layout runs only when a size or offset changed;
 * - a scroll change touches only nodes that are paged or scroll-animated, and only their
 *   transforms. No binding runs during a scroll.
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

    /** Background colour of the root, or null. */
    var background: Int? = null; private set

    private var viewportWidth = 0f
    private var viewportHeight = 0f
    private var scroll = 0f
    private var scrollDirty = true
    private var layoutDirty = true
    private val pendingContent = LinkedHashSet<RuntimeNode>()
    private val pendingTransform = LinkedHashSet<RuntimeNode>()

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
        get() = graph.hasPending || layoutDirty || scrollDirty || pendingContent.isNotEmpty() || pendingTransform.isNotEmpty()

    /** Runs pending bindings, layout and scroll animations. Returns true if anything changed. */
    fun update(): Boolean {
        contentChanged.clear()
        transformChanged.clear()
        val ran = graph.flush()
        if (layoutDirty) {
            layoutDirty = false
            layoutPass()
        }
        if (scrollDirty) {
            scrollDirty = false
            applyScroll()
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
            is Value.Bound -> effects += graph.effect {
                n.bindingRuns++
                val raw = value.template.evaluate(scope)
                val converted = value.convert(raw)
                if (converted != null) {
                    apply(converted)
                } else {
                    reportError("line ${value.line}: '${n.id}' $property got '${raw}', which isn't a valid value")
                }
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
                markTransform(n)
            }
        }
    }

    private class CompiledAnim(
        val range: ClosedFloatingPointRange<Float>,
        val easing: Easing,
        val props: Array<AnimProperty>,
        val values: Array<FloatArray>,
    )

    private companion object {
        const val DEFAULT_SIZE_DP = 100f
        const val MAX_ERRORS = 50
    }
}
