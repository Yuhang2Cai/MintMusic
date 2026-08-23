package com.example.timedmusicplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesAndSortsMultipleTimestamps() {
        val lines = LrcParser.parse("[00:02.50]第二句\n[00:01.2][00:03.250]第一句")
        assertEquals(listOf(1200L, 2500L, 3250L), lines.map(LyricLine::timeMs))
    }

    @Test fun resolvesCurrentLine() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(2000, "b"))
        assertEquals(-1, LrcParser.currentIndex(lines, 999))
        assertEquals(0, LrcParser.currentIndex(lines, 1500))
        assertEquals(1, LrcParser.currentIndex(lines, 3000))
    }
}
