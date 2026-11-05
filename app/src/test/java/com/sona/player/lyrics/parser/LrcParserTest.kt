package com.sona.player.lyrics.parser

import com.sona.player.lyrics.model.LyricSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [00:05.50]First line of lyrics
            [00:10.20]Second line of lyrics
            [01:00.00]Third line after one minute
        """.trimIndent()

        val lyrics = LrcParser.parse(1L, lrc, LyricSource.LOCAL_FILE)
        assertTrue(lyrics.isSynced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(5500L, lyrics.lines[0].startMs)
        assertEquals("First line of lyrics", lyrics.lines[0].text)
        assertEquals(10200L, lyrics.lines[1].startMs)
        assertEquals("Second line of lyrics", lyrics.lines[1].text)
        assertEquals(60000L, lyrics.lines[2].startMs)
    }

    @Test
    fun testWordByWordEnhancedLrc() {
        val lrc = """
            [00:02.00]<00:02.00>Never <00:02.50>gonna <00:03.00>give <00:03.50>you <00:04.00>up
        """.trimIndent()

        val lyrics = LrcParser.parse(1L, lrc, LyricSource.LRCLIB)
        assertTrue(lyrics.isSynced)
        assertEquals(1, lyrics.lines.size)
        val line = lyrics.lines[0]
        assertEquals("Never gonna give you up", line.text)
        assertEquals(5, line.words.size)
        assertEquals("Never", line.words[0].text)
        assertEquals(2000L, line.words[0].startMs)
        assertEquals(2500L, line.words[0].endMs)
    }

    @Test
    fun testGlobalOffsetTag() {
        val lrc = """
            [offset:+500]
            [00:02.00]Line with half second offset
        """.trimIndent()

        val lyrics = LrcParser.parse(1L, lrc, LyricSource.LOCAL_FILE)
        assertEquals(2500L, lyrics.lines[0].startMs)
    }

    @Test
    fun testFindActiveLineIndex() {
        val lrc = """
            [00:05.00]Line 1
            [00:10.00]Line 2
            [00:20.00]Line 3
        """.trimIndent()

        val lyrics = LrcParser.parse(1L, lrc, LyricSource.LOCAL_FILE)
        assertEquals(-1, lyrics.findActiveLineIndex(4999L))
        assertEquals(0, lyrics.findActiveLineIndex(5000L))
        assertEquals(0, lyrics.findActiveLineIndex(7000L))
        assertEquals(1, lyrics.findActiveLineIndex(10000L))
        assertEquals(1, lyrics.findActiveLineIndex(15000L))
        assertEquals(2, lyrics.findActiveLineIndex(25000L))

        // Test with user offset (+1000ms delay: lyrics should trigger 1s later)
        val offsetLyrics = lyrics.copy(userOffsetMs = 1000L)
        assertEquals(-1, offsetLyrics.findActiveLineIndex(5500L))
        assertEquals(0, offsetLyrics.findActiveLineIndex(6000L))
    }

    @Test
    fun testPlainLyricsFallback() {
        val plain = """
            Line without timestamps
            Another plain line
        """.trimIndent()

        val lyrics = LrcParser.parse(1L, plain, LyricSource.LOCAL_FILE)
        assertFalse(lyrics.isSynced)
        assertEquals(0, lyrics.lines.size)
        assertEquals(plain, lyrics.plainLyrics)
    }
}
