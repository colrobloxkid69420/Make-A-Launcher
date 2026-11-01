package io.github.makealauncher.core.expr

import io.github.makealauncher.core.data.DataSources
import io.github.makealauncher.core.reactive.Source
import java.time.format.DateTimeFormatter

/**
 * The data a template can read: `time.*`, `battery.*` and `globals.*`.
 *
 * Each member reads exactly the sources it needs, so dependencies stay as narrow as possible.
 * `time.format("HH:mm")` depends on the minute tick; `time.format("HH:mm:ss")` on the second tick.
 */
class DataScope(
    private val data: DataSources,
    private val globals: Map<String, Source<Any?>>,
) : EvalScope {

    private val formatters = HashMap<String, DateTimeFormatter>()

    override fun resolve(namespace: String, member: String, args: List<Any?>?): Any? = when (namespace) {
        "time" -> time(member, args)
        "battery" -> when (member) {
            "level" -> data.battery.level.value
            "charging" -> data.battery.charging.value
            else -> null
        }
        "globals" -> globals[member]?.value
        else -> null
    }

    private fun time(member: String, args: List<Any?>?): Any? {
        val t = data.time
        return when (member) {
            "format" -> {
                val pattern = args?.firstOrNull()?.toString() ?: return null
                if (needsSeconds(pattern)) t.secondTick.value else t.minuteTick.value
                val f = formatters.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }
                f.format(t.clock())
            }
            "hour" -> {
                t.minuteTick.value
                t.clock().hour
            }
            "minute" -> {
                t.minuteTick.value
                t.clock().minute
            }
            "second" -> {
                t.secondTick.value
                t.clock().second
            }
            else -> null
        }
    }

    companion object {
        fun schema(globalNames: Set<String>): Schema = Schema(
            mapOf(
                "time" to NamespaceSchema(
                    properties = setOf("hour", "minute", "second"),
                    functions = mapOf(
                        "format" to FunctionSpec(1..1) { args ->
                            val p = args[0] as? String ?: return@FunctionSpec "time.format needs a pattern in quotes, like \"HH:mm\""
                            try {
                                DateTimeFormatter.ofPattern(p)
                                null
                            } catch (e: IllegalArgumentException) {
                                "invalid time pattern \"$p\": ${e.message}"
                            }
                        },
                    ),
                ),
                "battery" to NamespaceSchema(properties = setOf("level", "charging")),
                "globals" to NamespaceSchema(properties = globalNames),
            ),
        )

        /** Seconds-level patterns: 's' (second), 'S' (fraction), 'A' (milli-of-day), 'n'/'N' (nanos). */
        fun needsSeconds(pattern: String): Boolean {
            var inQuote = false
            for (c in pattern) {
                if (c == '\'') inQuote = !inQuote
                if (!inQuote && (c == 's' || c == 'S' || c == 'A' || c == 'n' || c == 'N')) return true
            }
            return false
        }
    }
}
