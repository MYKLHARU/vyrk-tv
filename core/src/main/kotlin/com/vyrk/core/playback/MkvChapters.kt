package com.vyrk.core.playback

/**
 * Reads chapters from a Matroska/WebM file without downloading it: Media3 doesn't expose
 * container chapters. Uses at most two range reads: the first 64 KB (EBML header, Segment,
 * SeekHead and often Chapters), then, if needed, the Chapters element the SeekHead points to.
 *
 * Element IDs from the Matroska specification (RFC 9559).
 */
object MkvChapters {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val EDITION_HIDDEN = 0x45BDL
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val FLAG_HIDDEN = 0x98L
    private const val FLAG_ENABLED = 0x4598L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L
    private const val CLUSTER = 0x1F43B675L
    const val FIRST_READ = 64 * 1024
    private const val MAX_CHAPTERS_BYTES = 1024 * 1024

    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun has(n: Int) = pos + n <= end
        /** Element ID: VINT with its length marker kept. */
        fun id(): Long? {
            if (!has(1)) return null
            val first = b[pos].toInt() and 0xFF
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..4 || !has(len)) return null
            var v = 0L
            repeat(len) { v = (v shl 8) or (b[pos + it].toLong() and 0xFF) }
            pos += len
            return v
        }
        /** Element size: VINT with the marker removed; all ones means unknown (returned as -1). */
        fun size(): Long? {
            if (!has(1)) return null
            val first = b[pos].toInt() and 0xFF
            if (first == 0) return null
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..8 || !has(len)) return null
            var v = (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            for (i in 1 until len) {
                val byte = b[pos + i].toLong() and 0xFF
                if (byte != 0xFFL) allOnes = false
                v = (v shl 8) or byte
            }
            pos += len
            return if (allOnes) -1 else v
        }
        fun uint(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (b[pos + it].toLong() and 0xFF) }; pos += n; return v }
        fun text(n: Int): String { val s = String(b, pos, n, Charsets.UTF_8).trimEnd('\u0000'); pos += n; return s }
    }

    /** [fetch] reads [length] bytes from absolute [start]; it may return fewer at the end of the file. */
    suspend fun read(fetch: suspend (start: Long, length: Int) -> ByteArray?): List<Chapter> {
        val head = fetch(0, FIRST_READ) ?: return emptyList()
        val r = Reader(head)
        if (r.id() != EBML) return emptyList()
        val ebmlSize = r.size() ?: return emptyList()
        r.pos += ebmlSize.toInt()
        if (r.id() != SEGMENT) return emptyList()
        r.size() ?: return emptyList()
        val segmentData = r.pos.toLong()
        var chaptersAt: Long? = null
        while (r.has(2)) {
            val start = r.pos
            val id = r.id() ?: break
            val size = r.size() ?: break
            if (id == CLUSTER || size < 0) break
            val bodyEnd = r.pos + size
            when (id) {
                CHAPTERS -> if (bodyEnd <= head.size) return parseChapters(head, r.pos, bodyEnd.toInt())
                    else chaptersAt = start - segmentData
                SEEK_HEAD -> if (bodyEnd <= head.size) chaptersAt = chaptersAt ?: seekPosition(head, r.pos, bodyEnd.toInt(), CHAPTERS)
            }
            if (bodyEnd > head.size) break
            r.pos = bodyEnd.toInt()
        }
        val rel = chaptersAt ?: return emptyList()
        val abs = segmentData + rel
        val first = fetch(abs, 16) ?: return emptyList()
        val hr = Reader(first)
        if (hr.id() != CHAPTERS) return emptyList()
        val size = hr.size() ?: return emptyList()
        if (size <= 0 || size > MAX_CHAPTERS_BYTES) return emptyList()
        val body = fetch(abs + hr.pos, size.toInt()) ?: return emptyList()
        return parseChapters(body, 0, minOf(body.size, size.toInt()))
    }

    private fun seekPosition(b: ByteArray, from: Int, to: Int, target: Long): Long? {
        val r = Reader(b, from, to)
        while (r.has(2)) {
            val id = r.id() ?: return null
            val size = r.size()?.toInt() ?: return null
            val end = r.pos + size
            if (id == SEEK) {
                val s = Reader(b, r.pos, end)
                var seekId: Long? = null
                var position: Long? = null
                while (s.has(2)) {
                    val cid = s.id() ?: break
                    val cs = s.size()?.toInt() ?: break
                    when (cid) {
                        SEEK_ID -> seekId = Reader(b, s.pos, s.pos + cs).let { x -> x.uint(cs) }
                        SEEK_POSITION -> position = Reader(b, s.pos, s.pos + cs).uint(cs)
                    }
                    s.pos += cs
                }
                if (seekId == target && position != null) return position
            }
            r.pos = end
        }
        return null
    }

    private data class Ed(val hidden: Boolean, val default: Boolean, val chapters: List<Chapter>)

    internal fun parseChapters(b: ByteArray, from: Int, to: Int): List<Chapter> {
        val editions = mutableListOf<Ed>()
        val r = Reader(b, from, to)
        while (r.has(2)) {
            val id = r.id() ?: break
            val size = r.size()?.toInt() ?: break
            val end = minOf(r.pos + size, to)
            if (id == EDITION) editions += parseEdition(b, r.pos, end)
            r.pos = end
        }
        val chosen = editions.firstOrNull { it.default && !it.hidden } ?: editions.firstOrNull { !it.hidden } ?: return emptyList()
        return chosen.chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    private fun parseEdition(b: ByteArray, from: Int, to: Int): Ed {
        var hidden = false
        var default = false
        val chapters = mutableListOf<Chapter>()
        val r = Reader(b, from, to)
        while (r.has(2)) {
            val id = r.id() ?: break
            val size = r.size()?.toInt() ?: break
            val end = minOf(r.pos + size, to)
            when (id) {
                EDITION_HIDDEN -> hidden = Reader(b, r.pos, end).uint(size) == 1L
                EDITION_DEFAULT -> default = Reader(b, r.pos, end).uint(size) == 1L
                ATOM -> parseAtom(b, r.pos, end)?.let { chapters += it }
            }
            r.pos = end
        }
        return Ed(hidden, default, chapters)
    }

    private fun parseAtom(b: ByteArray, from: Int, to: Int): Chapter? {
        var startNs: Long? = null
        var title: String? = null
        var hidden = false
        var enabled = true
        val r = Reader(b, from, to)
        while (r.has(2)) {
            val id = r.id() ?: break
            val size = r.size()?.toInt() ?: break
            val end = minOf(r.pos + size, to)
            when (id) {
                TIME_START -> startNs = Reader(b, r.pos, end).uint(size)
                FLAG_HIDDEN -> hidden = Reader(b, r.pos, end).uint(size) == 1L
                FLAG_ENABLED -> enabled = Reader(b, r.pos, end).uint(size) != 0L
                DISPLAY -> if (title == null) {
                    val d = Reader(b, r.pos, end)
                    while (d.has(2)) {
                        val did = d.id() ?: break
                        val ds = d.size()?.toInt() ?: break
                        if (did == STRING) { title = Reader(b, d.pos, d.pos + ds).text(ds).takeIf { it.isNotBlank() }; break }
                        d.pos += ds
                    }
                }
            }
            r.pos = end
        }
        if (hidden || !enabled || startNs == null) return null
        // ChapterTimeStart is in nanoseconds, unscaled by TimestampScale.
        return Chapter(startNs / 1_000_000, title)
    }
}
