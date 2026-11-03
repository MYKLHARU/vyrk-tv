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
        val norm = s.replace(',', '.')
        val parts = norm.split(':')
        val (h, m, sec) = when (parts.size) {
            3 -> Triple(parts[0].toLong(), parts[1].toLong(), parts[2])
            2 -> Triple(0L, parts[0].toLong(), parts[1])
            else -> return 0
        }
        val secParts = sec.split('.')
        val whole = secParts[0].toLong()
        val frac = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
        return ((h * 3600 + m * 60 + whole) * 1000) + frac
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
            if (end <= start) continue
            cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.startMs }
    }

    fun isVtt(content: String) = content.removePrefix("﻿").trimStart().startsWith("WEBVTT")
}
