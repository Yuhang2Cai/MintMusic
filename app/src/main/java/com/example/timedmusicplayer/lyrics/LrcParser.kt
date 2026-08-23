package com.example.timedmusicplayer.lyrics

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(content: String): List<LyricLine> = buildList {
        content.lineSequence().forEach { raw ->
            val matches = timestamp.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val text = timestamp.replace(raw, "").trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                    2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                    3 -> fraction.toLongOrNull() ?: 0L
                    else -> 0L
                }
                add(LyricLine(minutes * 60_000L + seconds * 1_000L + millis, text))
            }
        }
    }.distinctBy { it.timeMs to it.text }.sortedBy(LyricLine::timeMs)

    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else high = mid - 1
        }
        return result
    }
}

