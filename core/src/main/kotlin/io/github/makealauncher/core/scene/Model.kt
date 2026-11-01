package io.github.makealauncher.core.scene

import io.github.makealauncher.core.expr.Template

/** A loaded preset: what the files describe, before anything is evaluated or drawn. */
class Preset(
    val manifest: Manifest,
    val scene: SceneSpec,
    val globals: List<GlobalSpec>,
)

class Manifest(
    val name: String,
    val author: String?,
    val version: String?,
    val schema: Int,
)

class SceneSpec(
    /** Number of home-screen pages the user can swipe between. */
    val pages: Int,
    val background: Value<Int>?,
    val layers: List<NodeSpec>,
)

enum class GlobalType { COLOR, NUMBER, TEXT, SWITCH }

class GlobalSpec(val name: String, val type: GlobalType, val value: Any?, val line: Int)

/** A property value: fixed, or bound to a template that re-evaluates when its data changes. */
sealed class Value<out T> {
    data class Const<T>(val value: T) : Value<T>()

    class Bound<T>(
        val template: Template,
        /** Converts the template result; null means "not a valid value for this property". */
        val convert: (Any?) -> T?,
        val line: Int,
        val property: String,
    ) : Value<T>()
}

/** Sizes and offsets are in dp; the runtime converts them to pixels. */
class NodeSpec(
    val id: String,
    val line: Int,
    /** Top-level layers with a page move with the home-screen scroll. Null = fixed on screen. */
    val page: Int?,
    val anchor: Anchor,
    val offsetX: Value<Float>,
    val offsetY: Value<Float>,
    val width: Value<Float>?,
    val height: Value<Float>?,
    val alpha: Value<Float>,
    val rotation: Value<Float>,
    val scale: Value<Float>,
    val visible: Value<Boolean>,
    val animations: List<AnimSpec>,
    val content: ContentSpec,
    val children: List<NodeSpec>,
)

sealed class ContentSpec {
    class Text(
        val text: Value<String>,
        val size: Value<Float>,
        val color: Value<Int>,
        val font: String?,
        val bold: Boolean,
        val align: TextAlign,
        val maxLines: Int,
    ) : ContentSpec()

    class Shape(
        val kind: ShapeKind,
        val radius: Value<Float>,
        val fill: Value<Int>?,
        val stroke: Value<Int>?,
        val strokeWidth: Value<Float>,
    ) : ContentSpec()

    class Image(
        val src: String,
        val fit: Fit,
        val tint: Value<Int>?,
    ) : ContentSpec()

    object Group : ContentSpec()
}

/** Where a node sits inside its parent: the same point of the node and the parent line up. */
enum class Anchor(val key: String, val fx: Float, val fy: Float) {
    TOP_LEFT("top-left", 0f, 0f),
    TOP_CENTER("top-center", 0.5f, 0f),
    TOP_RIGHT("top-right", 1f, 0f),
    CENTER_LEFT("center-left", 0f, 0.5f),
    CENTER("center", 0.5f, 0.5f),
    CENTER_RIGHT("center-right", 1f, 0.5f),
    BOTTOM_LEFT("bottom-left", 0f, 1f),
    BOTTOM_CENTER("bottom-center", 0.5f, 1f),
    BOTTOM_RIGHT("bottom-right", 1f, 1f),
}

/** start/end follow the text direction, so right-to-left languages align correctly. */
enum class TextAlign(val key: String) { START("start"), CENTER("center"), END("end") }

enum class ShapeKind(val key: String) { RECT("rect"), OVAL("oval") }

enum class Fit(val key: String) { COVER("cover"), CONTAIN("contain"), FILL("fill") }

enum class Easing(val key: String) { LINEAR("linear"), IN("in"), OUT("out"), IN_OUT("in-out") }

enum class AnimProperty(val key: String) {
    TRANSLATE_X("translate_x"),
    TRANSLATE_Y("translate_y"),
    ALPHA("alpha"),
    SCALE("scale"),
    ROTATION("rotation"),
}

/**
 * A declarative animation bound to the home-screen scroll position (in pages). The renderer
 * interpolates it natively every frame; no template or script runs while scrolling.
 *
 * Each track lists values spread evenly across [range]; translate values are in dp,
 * rotation in degrees.
 */
class AnimSpec(
    val range: ClosedFloatingPointRange<Float>?,
    val easing: Easing,
    val tracks: Map<AnimProperty, FloatArray>,
    val line: Int,
)
