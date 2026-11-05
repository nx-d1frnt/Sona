package com.sona.player.lyrics.parser

import com.sona.player.lyrics.model.LyricLine
import com.sona.player.lyrics.model.LyricSource
import com.sona.player.lyrics.model.LyricWord
import com.sona.player.lyrics.model.TrackLyrics
import java.util.regex.Pattern

object LrcParser {

    private val LINE_TAG_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val WORD_TAG_PATTERN = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)

    /**
     * Parses an LRC string into structured [TrackLyrics].
     */
    fun parse(
        songId: Long,
        lrcContent: String?,
        source: LyricSource = LyricSource.LOCAL_FILE,
        initialUserOffsetMs: Long = 0L
    ): TrackLyrics {
        if (lrcContent.isNullOrBlank()) {
            return TrackLyrics(songId = songId, lines = emptyList(), plainLyrics = null, isSynced = false, source = source)
        }

        var globalOffsetMs = 0L
        val parsedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()

        val rawLines = lrcContent.lineSequence()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            // Check metadata offset tag [offset:+/-ms]
            val offsetMatcher = OFFSET_PATTERN.matcher(trimmed)
            if (offsetMatcher.find()) {
                globalOffsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            // Check for line timestamps
            val lineMatcher = LINE_TAG_PATTERN.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastTagEnd = 0

            while (lineMatcher.find()) {
                val min = lineMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = lineMatcher.group(2)?.toLongOrNull() ?: 0L
                val fracStr = lineMatcher.group(3)
                val ms = parseFractionToMs(fracStr)
                val totalMs = min * 60_000L + sec * 1_000L + ms
                timestamps.add(totalMs)
                lastTagEnd = lineMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val lineBody = trimmed.substring(lastTagEnd).trim()
                val (cleanedText, words) = parseWordTimestamps(lineBody)

                for (ts in timestamps) {
                    val adjustedStartMs = (ts + globalOffsetMs).coerceAtLeast(0L)
                    val shiftedWords = if (globalOffsetMs != 0L) {
                        words.map { it.copy(startMs = (it.startMs + globalOffsetMs).coerceAtLeast(0L), endMs = (it.endMs + globalOffsetMs).coerceAtLeast(0L)) }
                    } else {
                        words
                    }
                    parsedLines.add(LyricLine(startMs = adjustedStartMs, text = cleanedText, words = shiftedWords))
                }
            } else {
                // If it's not a known header like [ti:...], treat as plain lyric line
                if (!trimmed.startsWith("[") || !trimmed.contains("]")) {
                    plainLines.add(trimmed)
                }
            }
        }

        return if (parsedLines.isNotEmpty()) {
            parsedLines.sortBy { it.startMs }
            TrackLyrics(
                songId = songId,
                lines = parsedLines,
                plainLyrics = parsedLines.joinToString("\n") { it.text },
                isSynced = true,
                userOffsetMs = initialUserOffsetMs,
                source = source
            )
        } else {
            val plainText = if (plainLines.isNotEmpty()) plainLines.joinToString("\n") else lrcContent.trim()
            TrackLyrics(
                songId = songId,
                lines = emptyList(),
                plainLyrics = plainText,
                isSynced = false,
                userOffsetMs = initialUserOffsetMs,
                source = source
            )
        }
    }

    /**
     * Parses inline word tags like `<00:12.34> word <00:13.00>`
     */
    private fun parseWordTimestamps(lineBody: String): Pair<String, List<LyricWord>> {
        val wordMatcher = WORD_TAG_PATTERN.matcher(lineBody)
        if (!wordMatcher.find()) {
            return Pair(lineBody, emptyList())
        }

        // Reset and collect tokens
        wordMatcher.reset()
        val words = mutableListOf<LyricWord>()
        val textBuilder = StringBuilder()

        var currentStartMs: Long? = null
        var lastIndex = 0

        while (wordMatcher.find()) {
            val matchStart = wordMatcher.start()
            val matchEnd = wordMatcher.end()

            val textBetween = lineBody.substring(lastIndex, matchStart).trim()
            if (textBetween.isNotEmpty() && currentStartMs != null) {
                val min = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = wordMatcher.group(2)?.toLongOrNull() ?: 0L
                val ms = parseFractionToMs(wordMatcher.group(3))
                val nextMs = min * 60_000L + sec * 1_000L + ms

                words.add(LyricWord(text = textBetween, startMs = currentStartMs, endMs = nextMs))
                textBuilder.append(textBetween).append(" ")
            }

            val min = wordMatcher.group(1)?.toLongOrNull() ?: 0L
            val sec = wordMatcher.group(2)?.toLongOrNull() ?: 0L
            val ms = parseFractionToMs(wordMatcher.group(3))
            currentStartMs = min * 60_000L + sec * 1_000L + ms
            lastIndex = matchEnd
        }

        val trailingText = lineBody.substring(lastIndex).trim()
        if (trailingText.isNotEmpty() && currentStartMs != null) {
            // Assume 600ms duration for the final word if no closing timestamp
            words.add(LyricWord(text = trailingText, startMs = currentStartMs, endMs = currentStartMs + 600L))
            textBuilder.append(trailingText)
        }

        val cleanText = textBuilder.toString().trim()
        return Pair(if (cleanText.isNotEmpty()) cleanText else lineBody, words)
    }

    private fun parseFractionToMs(fracStr: String?): Long {
        if (fracStr.isNullOrEmpty()) return 0L
        return when (fracStr.length) {
            1 -> fracStr.toLong() * 100L
            2 -> fracStr.toLong() * 10L
            else -> fracStr.take(3).toLong()
        }
    }
}
