package io.github.makealauncher.core.runtime

/**
 * A stretch of consecutive top-level layers on the same page. The whole run moves with the
 * home-screen scroll as one piece and sleeps as one piece, so scrolling a page of a hundred
 * layers changes one value instead of a hundred.
 *
 * Runs keep the scene's drawing order: layers are only grouped while they're next to each
 * other in `scene.yaml`, so a layer that isn't on a page (or is on another page) between them
 * starts a new run.
 */
class PageRun internal constructor(val page: Int) {
    /** In drawing order: [SubRun]s of layers that don't animate, and layers that do. */
    val parts = ArrayList<Any>()

    /** Every top-level layer in this run. */
    val members = ArrayList<RuntimeNode>()

    /** Sideways offset of the page from the screen, in px. */
    var shift = 0f; internal set

    /** False when nothing in the run can be on screen: it isn't drawn and doesn't move. */
    var awake = true; internal set

    /** Platform render object (a RenderNode on Android). */
    var platform: Any? = null

    internal var moved = false
    internal var changed = false
    internal var boundsDirty = true

    override fun toString() = "PageRun(page=$page, ${members.size} layers)"
}

/**
 * Consecutive layers in a run that don't animate on their own. While the run is awake they're
 * drawn once into a single GPU layer, so scrolling only moves that cached image; it's
 * re-rendered only where something inside changes.
 */
class SubRun internal constructor(val run: PageRun) {
    val nodes = ArrayList<RuntimeNode>()

    /** What the layers paint, in page coordinates (px); null while nothing has been laid out. */
    var bounds: LayerBounds? = null; internal set

    /** True while this stretch is cached as a GPU layer. */
    var layered = false; internal set

    /** Platform render object (a RenderNode on Android). */
    var platform: Any? = null
}
