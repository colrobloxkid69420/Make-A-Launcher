package io.github.makealauncher.core.yaml

/**
 * A parsed YAML value. Every node remembers the line it came from so the preset loader
 * can point users at the exact line of a mistake.
 */
sealed class YamlNode {
    abstract val line: Int
}

/** A scalar. [quoted] is true for '...' and "..." values, which are never treated as null. */
data class YamlScalar(val text: String, val quoted: Boolean, override val line: Int) : YamlNode() {
    val isNull: Boolean get() = !quoted && (text.isEmpty() || text == "~" || text == "null")
}

data class YamlList(val items: List<YamlNode>, override val line: Int) : YamlNode()

/** A mapping. Keys keep their file order, which the editor relies on to write stable diffs. */
data class YamlMap(
    val entries: Map<String, YamlNode>,
    val keyLines: Map<String, Int>,
    override val line: Int,
) : YamlNode() {
    operator fun get(key: String): YamlNode? = entries[key]
}

class YamlException(val reason: String, val line: Int) : Exception("line $line: $reason")
