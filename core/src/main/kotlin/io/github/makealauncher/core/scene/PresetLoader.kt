package io.github.makealauncher.core.scene

import io.github.makealauncher.core.expr.DataScope
import io.github.makealauncher.core.expr.ExprException
import io.github.makealauncher.core.expr.Schema
import io.github.makealauncher.core.expr.Template
import io.github.makealauncher.core.expr.suggest
import io.github.makealauncher.core.yaml.Yaml
import io.github.makealauncher.core.yaml.YamlException
import io.github.makealauncher.core.yaml.YamlList
import io.github.makealauncher.core.yaml.YamlMap
import io.github.makealauncher.core.yaml.YamlNode
import io.github.makealauncher.core.yaml.YamlScalar

/** Reads files from a preset folder (Android assets, a zip, a test map...). */
fun interface PresetFiles {
    /** Returns the file's text, or null if it doesn't exist. */
    fun read(path: String): String?
}

enum class Severity { ERROR, WARNING }

class Problem(val severity: Severity, val file: String, val line: Int, val message: String) {
    override fun toString() = "${severity.name.lowercase()}: $file:$line: $message"
}

class LoadResult(val preset: Preset?, val problems: List<Problem>) {
    val errors: List<Problem> get() = problems.filter { it.severity == Severity.ERROR }
}

/**
 * Turns a preset folder (`preset.yaml`, `globals.yaml`, `scene.yaml`) into a [Preset].
 *
 * Problems are collected rather than thrown, so one load reports every mistake with its
 * file and line. The preset is null only if there were errors.
 */
object PresetLoader {
    const val SCHEMA_VERSION = 1

    fun load(files: PresetFiles): LoadResult {
        val problems = ArrayList<Problem>()
        val manifest = loadManifest(files, problems)
        val globals = loadGlobals(files, problems)
        val schema = DataScope.schema(globals.map { it.name }.toSet())
        val sceneText = files.read("scene.yaml")
        if (sceneText == null) {
            problems += Problem(Severity.ERROR, "scene.yaml", 1, "scene.yaml is missing")
            return LoadResult(null, problems)
        }
        val scene = Ctx("scene.yaml", problems, schema).parseFile(sceneText)?.let { root ->
            SceneReader(Ctx("scene.yaml", problems, schema)).read(root)
        }
        val failed = problems.any { it.severity == Severity.ERROR } || scene == null
        return LoadResult(if (failed) null else Preset(manifest, scene!!, globals), problems)
    }

    private fun loadManifest(files: PresetFiles, problems: MutableList<Problem>): Manifest {
        val text = files.read("preset.yaml") ?: return Manifest("Untitled", null, null, SCHEMA_VERSION)
        val ctx = Ctx("preset.yaml", problems, Schema(emptyMap()))
        val root = ctx.parseFile(text) as? YamlMap ?: return Manifest("Untitled", null, null, SCHEMA_VERSION)
        ctx.checkKeys(root, setOf("name", "author", "version", "schema", "description", "capabilities"), "preset.yaml")
        val schemaVersion = (root["schema"] as? YamlScalar)?.text?.toIntOrNull() ?: SCHEMA_VERSION
        if (schemaVersion > SCHEMA_VERSION) {
            ctx.error(root["schema"]!!.line, "this preset needs schema $schemaVersion; this app supports up to $SCHEMA_VERSION")
        }
        return Manifest(
            name = (root["name"] as? YamlScalar)?.text ?: "Untitled",
            author = (root["author"] as? YamlScalar)?.text,
            version = (root["version"] as? YamlScalar)?.text,
            schema = schemaVersion,
        )
    }

    private fun loadGlobals(files: PresetFiles, problems: MutableList<Problem>): List<GlobalSpec> {
        val text = files.read("globals.yaml") ?: return emptyList()
        val ctx = Ctx("globals.yaml", problems, Schema(emptyMap()))
        val root = ctx.parseFile(text) ?: return emptyList()
        if (root !is YamlMap) {
            ctx.error(root.line, "globals.yaml should be a list of 'name: value' entries")
            return emptyList()
        }
        val out = ArrayList<GlobalSpec>()
        for ((name, node) in root.entries) {
            val line = root.keyLines[name] ?: node.line
            if (!name.matches(IDENT)) {
                ctx.error(line, "global name '$name' must use letters, digits and _ only")
                continue
            }
            val spec = when (node) {
                is YamlScalar -> inferGlobal(name, node, ctx)
                is YamlMap -> explicitGlobal(name, node, ctx)
                else -> {
                    ctx.error(line, "global '$name' should be a value or {type: ..., value: ...}")
                    null
                }
            }
            if (spec != null) out += spec
        }
        return out
    }

    private fun inferGlobal(name: String, s: YamlScalar, ctx: Ctx): GlobalSpec {
        val t = s.text
        return when {
            !s.quoted && (t == "true" || t == "false") -> GlobalSpec(name, GlobalType.SWITCH, t == "true", s.line)
            !s.quoted && t.toDoubleOrNull() != null -> GlobalSpec(name, GlobalType.NUMBER, t.toDouble(), s.line)
            t.startsWith("#") -> {
                val c = parseColor(t)
                if (c == null) ctx.error(s.line, "'$t' isn't a colour; use #RRGGBB or #AARRGGBB")
                GlobalSpec(name, GlobalType.COLOR, c ?: 0, s.line)
            }
            else -> GlobalSpec(name, GlobalType.TEXT, t, s.line)
        }
    }

    private fun explicitGlobal(name: String, m: YamlMap, ctx: Ctx): GlobalSpec? {
        ctx.checkKeys(m, setOf("type", "value", "label"), "global '$name'")
        val typeText = (m["type"] as? YamlScalar)?.text
        val type = GlobalType.entries.firstOrNull { it.name.equals(typeText, ignoreCase = true) }
        if (type == null) {
            ctx.error(m.line, "global '$name' needs a type: color, number, text or switch")
            return null
        }
        val v = m["value"] as? YamlScalar
        val value: Any? = when (type) {
            GlobalType.COLOR -> v?.text?.let { parseColor(it) } ?: run { ctx.error(m.line, "global '$name' needs a colour value like '#FF336699'"); 0 }
            GlobalType.NUMBER -> v?.text?.toDoubleOrNull() ?: run { ctx.error(m.line, "global '$name' needs a number value"); 0.0 }
            GlobalType.TEXT -> v?.text ?: ""
            GlobalType.SWITCH -> when (v?.text) {
                "true" -> true
                "false", null -> false
                else -> run { ctx.error(m.line, "global '$name' needs true or false"); false }
            }
        }
        return GlobalSpec(name, type, value, m.line)
    }

    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
}

/** Accepts #RRGGBB and #AARRGGBB (Android / KLWP order). Returns ARGB or null. */
fun parseColor(text: String): Int? {
    val t = text.trim()
    if (!t.startsWith("#")) return null
    val hex = t.substring(1)
    val v = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000L or v).toInt()
        8 -> v.toInt()
        else -> null
    }
}

internal class Ctx(val file: String, private val problems: MutableList<Problem>, val schema: Schema) {
    fun error(line: Int, message: String) {
        problems += Problem(Severity.ERROR, file, line, message)
    }

    fun warn(line: Int, message: String) {
        problems += Problem(Severity.WARNING, file, line, message)
    }

    fun parseFile(text: String): YamlNode? = try {
        Yaml.parse(text)
    } catch (e: YamlException) {
        error(e.line, e.reason)
        null
    }

    fun checkKeys(m: YamlMap, allowed: Set<String>, what: String) {
        for (k in m.entries.keys) {
            if (k !in allowed) {
                error(m.keyLines[k] ?: m.line, "unknown property '$k' on $what" + suggest(k, allowed))
            }
        }
    }
}

private class SceneReader(private val ctx: Ctx) {
    private val ids = HashMap<String, Int>()
    private var autoId = 0

    fun read(root: YamlNode): SceneSpec? {
        if (root !is YamlMap) {
            ctx.error(root.line, "scene.yaml should start with 'pages:' and 'layers:'")
            return null
        }
        ctx.checkKeys(root, setOf("pages", "background", "layers"), "the scene")
        val pages = root["pages"]?.let { n ->
            val p = (n as? YamlScalar)?.text?.toIntOrNull()
            if (p == null || p < 1 || p > 20) {
                ctx.error(n.line, "pages must be a whole number from 1 to 20")
                1
            } else p
        } ?: 1
        val background = root["background"]?.let { color(it, "background") }
        val layersNode = root["layers"]
        val layers = when (layersNode) {
            null -> emptyList()
            is YamlList -> layersNode.items.mapNotNull { node(it, topLevel = true, pages = pages) }
            else -> {
                ctx.error(layersNode.line, "layers should be a list, with each layer starting with '- '")
                emptyList()
            }
        }
        return SceneSpec(pages, background, layers)
    }

    private fun node(n: YamlNode, topLevel: Boolean, pages: Int): NodeSpec? {
        if (n !is YamlMap) {
            ctx.error(n.line, "each layer should be a set of 'key: value' properties")
            return null
        }
        val typeText = (n["type"] as? YamlScalar)?.text
        val type = typeText ?: run {
            ctx.error(n.line, "layer is missing 'type' (text, shape, image or group)")
            return null
        }
        val allowed = COMMON_KEYS + when (type) {
            "text" -> TEXT_KEYS
            "shape" -> SHAPE_KEYS
            "image" -> IMAGE_KEYS
            "group" -> GROUP_KEYS
            else -> {
                ctx.error(n["type"]!!.line, "unknown layer type '$type'" + suggest(type, listOf("text", "shape", "image", "group")))
                return null
            }
        }
        val idNode = n["id"] as? YamlScalar
        val id = if (idNode == null || idNode.text.isBlank()) {
            val gen = "$type-${++autoId}"
            ctx.warn(n.line, "layer has no id; using '$gen'. Give every layer an id so edits and comments stay attached to it")
            gen
        } else {
            idNode.text
        }
        ids[id]?.let { firstLine ->
            ctx.error(idNode?.line ?: n.line, "duplicate id '$id' (first used on line $firstLine)")
        }
        ids.putIfAbsent(id, idNode?.line ?: n.line)
        ctx.checkKeys(n, allowed, "$type '$id'")

        val page = n["page"]?.let { p ->
            val v = (p as? YamlScalar)?.text?.toIntOrNull()
            when {
                !topLevel -> {
                    ctx.error(p.line, "'page' only works on top-level layers")
                    null
                }
                v == null || v < 0 || v >= pages -> {
                    ctx.error(p.line, "page must be from 0 to ${pages - 1}")
                    null
                }
                else -> v
            }
        }
        val anchor = n["anchor"]?.let { a ->
            val t = (a as? YamlScalar)?.text
            Anchor.entries.firstOrNull { it.key == t } ?: run {
                ctx.error(a.line, "unknown anchor '$t'" + suggest(t ?: "", Anchor.entries.map { it.key }))
                null
            }
        } ?: Anchor.TOP_LEFT

        var offsetX: Value<Float> = Value.Const(0f)
        var offsetY: Value<Float> = Value.Const(0f)
        n["offset"]?.let { o ->
            if (o is YamlList && o.items.size == 2) {
                offsetX = number(o.items[0], "offset x")
                offsetY = number(o.items[1], "offset y")
            } else {
                ctx.error(o.line, "offset should be [x, y], e.g. offset: [0, 120]")
            }
        }

        val content: ContentSpec = when (type) {
            "text" -> ContentSpec.Text(
                text = text(n["text"], "text"),
                size = n["size"]?.let { number(it, "size") } ?: Value.Const(16f),
                color = n["color"]?.let { color(it, "color") } ?: Value.Const(0xFFFFFFFF.toInt()),
                font = (n["font"] as? YamlScalar)?.text,
                bold = bool(n["bold"], "bold", false),
                align = enumOf(n["align"], TextAlign.entries, { it.key }, TextAlign.START, "align"),
                maxLines = n["max_lines"]?.let { m ->
                    (m as? YamlScalar)?.text?.toIntOrNull()?.takeIf { it > 0 } ?: run {
                        ctx.error(m.line, "max_lines must be a positive whole number")
                        0
                    }
                } ?: 0,
            )
            "shape" -> ContentSpec.Shape(
                kind = enumOf(n["shape"], ShapeKind.entries, { it.key }, ShapeKind.RECT, "shape"),
                radius = n["radius"]?.let { number(it, "radius") } ?: Value.Const(0f),
                fill = n["fill"]?.let { color(it, "fill") },
                stroke = n["stroke"]?.let { color(it, "stroke") },
                strokeWidth = n["stroke_width"]?.let { number(it, "stroke_width") } ?: Value.Const(1f),
            )
            "image" -> ContentSpec.Image(
                src = (n["src"] as? YamlScalar)?.text ?: run {
                    ctx.error(n.line, "image '$id' needs src: path/to/file.png")
                    ""
                },
                fit = enumOf(n["fit"], Fit.entries, { it.key }, Fit.COVER, "fit"),
                tint = n["tint"]?.let { color(it, "tint") },
            )
            else -> ContentSpec.Group
        }

        val children = if (type == "group") {
            when (val c = n["children"]) {
                null -> emptyList()
                is YamlList -> c.items.mapNotNull { node(it, topLevel = false, pages = pages) }
                else -> {
                    ctx.error(c.line, "children should be a list of layers")
                    emptyList()
                }
            }
        } else emptyList()

        return NodeSpec(
            id = id,
            line = n.line,
            page = page,
            anchor = anchor,
            offsetX = offsetX,
            offsetY = offsetY,
            width = n["width"]?.let { number(it, "width") },
            height = n["height"]?.let { number(it, "height") },
            alpha = n["alpha"]?.let { number(it, "alpha") } ?: Value.Const(1f),
            rotation = n["rotation"]?.let { number(it, "rotation") } ?: Value.Const(0f),
            scale = n["scale"]?.let { number(it, "scale") } ?: Value.Const(1f),
            visible = n["visible"]?.let { boolValue(it, "visible") } ?: Value.Const(true),
            animations = animations(n["animate"], id),
            content = content,
            children = children,
        )
    }

    private fun animations(n: YamlNode?, id: String): List<AnimSpec> {
        if (n == null) return emptyList()
        if (n !is YamlList) {
            ctx.error(n.line, "animate should be a list, each starting with '- on: scroll'")
            return emptyList()
        }
        return n.items.mapNotNull { a ->
            if (a !is YamlMap) {
                ctx.error(a.line, "each animation should be a set of 'key: value' properties")
                return@mapNotNull null
            }
            ctx.checkKeys(a, ANIM_KEYS, "an animation on '$id'")
            val on = (a["on"] as? YamlScalar)?.text
            if (on != "scroll") {
                ctx.error(a.line, "animation trigger must be 'on: scroll' (more triggers are coming)")
                return@mapNotNull null
            }
            val range = a["range"]?.let { r ->
                val nums = (r as? YamlList)?.items?.map { (it as? YamlScalar)?.text?.toFloatOrNull() }
                if (nums == null || nums.size != 2 || nums.any { it == null } || nums[0] == nums[1]) {
                    ctx.error(r.line, "range should be two different page numbers, e.g. [0, 1]")
                    null
                } else nums[0]!!..nums[1]!!
            }
            val easing = enumOf(a["ease"], Easing.entries, { it.key }, Easing.LINEAR, "ease")
            val tracks = LinkedHashMap<AnimProperty, FloatArray>()
            for (p in AnimProperty.entries) {
                val t = a[p.key] ?: continue
                val values = (t as? YamlList)?.items?.map { (it as? YamlScalar)?.text?.toFloatOrNull() }
                if (values == null || values.size < 2 || values.any { it == null }) {
                    ctx.error(t.line, "${p.key} should list at least two numbers, e.g. [0, -200]")
                    continue
                }
                tracks[p] = FloatArray(values.size) { values[it]!! }
            }
            if (tracks.isEmpty()) {
                ctx.error(a.line, "animation changes nothing; add translate_x, translate_y, alpha, scale or rotation")
                return@mapNotNull null
            }
            AnimSpec(range, easing, tracks, a.line)
        }
    }

    // ---- values ---------------------------------------------------------------------------

    private fun scalarOrHint(n: YamlNode, property: String): YamlScalar? = when (n) {
        is YamlScalar -> n
        is YamlMap -> {
            ctx.error(n.line, "$property: values that start with '{' must be in quotes, e.g. $property: '{globals.accent}'")
            null
        }
        else -> {
            ctx.error(n.line, "$property should be a single value")
            null
        }
    }

    private fun template(s: YamlScalar, property: String): Template? = try {
        Template.parse(s.text, ctx.schema)
    } catch (e: ExprException) {
        ctx.error(s.line, "$property: ${e.message}")
        null
    }

    private fun <T> bound(s: YamlScalar, property: String, convert: (Any?) -> T?, fallback: T): Value<T> {
        val t = template(s, property) ?: return Value.Const(fallback)
        if (t.isConstant) {
            val v = convert(t.evaluate { _, _, _ -> null })
            if (v == null) {
                ctx.error(s.line, "$property: '${s.text}' isn't valid here")
                return Value.Const(fallback)
            }
            return Value.Const(v)
        }
        return Value.Bound(t, convert, s.line, property)
    }

    fun number(n: YamlNode, property: String): Value<Float> {
        val s = scalarOrHint(n, property) ?: return Value.Const(0f)
        s.text.trim().toFloatOrNull()?.let { return Value.Const(it) }
        if (!Template.hasCode(s.text)) {
            ctx.error(s.line, "$property should be a number")
            return Value.Const(0f)
        }
        return bound(s, property, ::toFloat, 0f)
    }

    fun color(n: YamlNode, property: String): Value<Int> {
        val s = scalarOrHint(n, property) ?: return Value.Const(0)
        if (!Template.hasCode(s.text)) {
            val c = parseColor(s.text)
            if (c == null) ctx.error(s.line, "$property: '${s.text}' isn't a colour; use #RRGGBB or #AARRGGBB")
            return Value.Const(c ?: 0)
        }
        return bound(s, property, ::toColor, 0)
    }

    fun text(n: YamlNode?, property: String): Value<String> {
        if (n == null) return Value.Const("")
        val s = scalarOrHint(n, property) ?: return Value.Const("")
        return bound(s, property, { v -> Template.stringify(v) }, "")
    }

    fun boolValue(n: YamlNode, property: String): Value<Boolean> {
        val s = scalarOrHint(n, property) ?: return Value.Const(true)
        if (!s.quoted && (s.text == "true" || s.text == "false")) return Value.Const(s.text == "true")
        if (!Template.hasCode(s.text)) {
            ctx.error(s.line, "$property should be true or false")
            return Value.Const(true)
        }
        return bound(s, property, ::toBool, true)
    }

    private fun bool(n: YamlNode?, property: String, default: Boolean): Boolean {
        if (n == null) return default
        val t = (n as? YamlScalar)?.text
        return when (t) {
            "true" -> true
            "false" -> false
            else -> {
                ctx.error(n.line, "$property should be true or false")
                default
            }
        }
    }

    private fun <E> enumOf(n: YamlNode?, values: List<E>, key: (E) -> String, default: E, property: String): E {
        if (n == null) return default
        val t = (n as? YamlScalar)?.text ?: ""
        return values.firstOrNull { key(it) == t } ?: run {
            ctx.error(n.line, "$property: unknown value '$t'; use one of ${values.joinToString { key(it) }}" + suggest(t, values.map(key)))
            default
        }
    }

    companion object {
        val COMMON_KEYS = setOf("id", "type", "page", "anchor", "offset", "width", "height", "alpha", "rotation", "scale", "visible", "animate")
        val TEXT_KEYS = setOf("text", "size", "color", "font", "bold", "align", "max_lines")
        val SHAPE_KEYS = setOf("shape", "radius", "fill", "stroke", "stroke_width")
        val IMAGE_KEYS = setOf("src", "fit", "tint")
        val GROUP_KEYS = setOf("children")
        val ANIM_KEYS = setOf("on", "range", "ease") + AnimProperty.entries.map { it.key }
    }
}

fun toFloat(v: Any?): Float? = when (v) {
    is Number -> v.toFloat()
    is String -> v.trim().toFloatOrNull()
    is Boolean -> if (v) 1f else 0f
    else -> null
}

fun toColor(v: Any?): Int? = when (v) {
    is Int -> v
    is Number -> v.toLong().toInt()
    is String -> parseColor(v)
    else -> null
}

fun toBool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> when (v.trim().lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0", "" -> false
        else -> null
    }
    null -> false
    else -> null
}
