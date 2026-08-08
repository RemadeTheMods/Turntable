package com.turntable.app

/**
 * Parses the standard LRC lyrics format: lines like "[01:23.45]Some lyric text".
 * A line can carry more than one timestamp tag (repeated choruses reusing the
 * same text at different times), which this handles by emitting one entry per
 * tag. Non-lyric metadata tags (like [ar:Artist] or [ti:Title]) are ignored.
 */
object LrcParser {

    data class LyricLine(val timeMs: Long, val text: String)

    private val TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun parse(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (rawLine in lrc.lines()) {
            val matches = TAG_REGEX.findAll(rawLine).toList()
            if (matches.isEmpty()) continue

            val text = rawLine.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val seconds = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val fracMs = if (fracRaw.isEmpty()) 0L else fracRaw.padEnd(3, '0').take(3).toLong()
                val timeMs = minutes * 60_000L + seconds * 1000L + fracMs
                result.add(LyricLine(timeMs, text))
            }
        }
        return result.sortedBy { it.timeMs }
    }

    fun isSynced(text: String): Boolean = TAG_REGEX.containsMatchIn(text)
}
