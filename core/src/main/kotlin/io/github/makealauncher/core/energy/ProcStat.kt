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
}
