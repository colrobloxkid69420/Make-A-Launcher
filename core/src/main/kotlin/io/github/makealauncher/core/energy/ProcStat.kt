package io.github.makealauncher.core.energy

/** Parses Linux /proc/<pid>/task/<tid>/stat lines, which Android apps can read for their own threads. */
object ProcStat {
    /**
     * User + system CPU time in clock ticks from a stat line, or null if it can't be parsed.
     * The thread name (second field) is in parentheses and may contain spaces, so fields are
     * counted from the last ')'.
     */
    fun cpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        // After the name: state(3) ... utime(14) stime(15); index 0 here is field 3.
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** The thread name from a stat line, or null. */
    fun threadName(stat: String): String? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return stat.substring(open + 1, close)
    }

    /**
     * Groups numbered threads of the same kind, e.g. "binder:1234_2" and "binder:1234_5" →
     * "binder", "hwuiTask1" → "hwuiTask".
     */
    fun groupName(name: String): String =
        name.substringBefore(':').trimEnd { it.isDigit() || it == '_' || it == '-' || it == ' ' }.ifEmpty { name }

    /**
     * CPU used by each thread group between two snapshots (thread id → name and ms), largest
     * first. Threads that started after [before] count from zero.
     */
    fun busiestGroups(
        before: Map<Int, Pair<String, Long>>,
        after: Map<Int, Pair<String, Long>>,
        exclude: Set<Int> = emptySet(),
    ): List<Pair<String, Long>> {
        val totals = HashMap<String, Long>()
        for ((tid, now) in after) {
            if (tid in exclude) continue
            val used = now.second - (before[tid]?.second ?: 0L)
            if (used <= 0) continue
            val group = groupName(now.first)
            totals[group] = (totals[group] ?: 0L) + used
        }
        return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
