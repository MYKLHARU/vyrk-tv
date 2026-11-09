package com.vyrk.core.subtitles

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * SRT and WebVTT parsing with safe markup stripping. Braces are escaped in every regex: an
 * unescaped `}` once crashed subtitle selection on Android's regex engine.
 */
object SubtitleParser {
    private val assTags = Regex("""\{\\[^\}]*\}""")
    private val anyBraces = Regex("""\{[^\}]*\}""")
    private val htmlTags = Regex("""</?[a-zA-Z][^>]*>""")
    private val vttTimestampTags = Regex("""<\d{2}:\d{2}[:.\d]*>""")
    private val timeLine = Regex("""((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})""")

    fun stripMarkup(text: String): String {
        var t = assTags.replace(text, "")
        t = anyBraces.replace(t, "")
        t = vttTimestampTags.replace(t, "")
        t = htmlTags.replace(t, "")
        t = t.replace("\\N", "\n").replace("\\n", "\n").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun parseTime(s: String): Long {
        val parts = s.replace(',', '.').split(':')
        if (parts.size !in 2..3) return -1
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return -1 else 0L
        val m = parts[parts.size - 2].toLongOrNull() ?: return -1
        val seconds = parts.last().split('.')
        val whole = seconds[0].toLongOrNull() ?: return -1
        val frac = seconds.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
        if (h < 0 || m !in 0..59 || whole !in 0..59 || h > (Long.MAX_VALUE - 3_599_999) / 3_600_000) return -1
        return h * 3_600_000 + m * 60_000 + whole * 1000 + frac
    }

    fun parse(content: String): List<Cue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<Cue>()
        for (block in blocks) {
            val lines = block.lines()
            val idx = lines.indexOfFirst { timeLine.containsMatchIn(it) }
            if (idx < 0) continue
            val m = timeLine.find(lines[idx]) ?: continue
            val body = stripMarkup(lines.drop(idx + 1).joinToString("\n"))
            if (body.isEmpty()) continue
            val start = parseTime(m.groupValues[1])
            val end = parseTime(m.groupValues[2])
            if (start < 0 || end <= start) continue
            cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.startMs }
    }

    fun isVtt(content: String) = content.removePrefix("﻿").trimStart().startsWith("WEBVTT")
}
