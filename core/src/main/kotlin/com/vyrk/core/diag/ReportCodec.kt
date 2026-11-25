package com.vyrk.core.diag

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A playback report packed into a link: raw DEFLATE, then URL-safe Base64, after "#r=" on the
 * report page. Everything after "#" stays in the browser (it's never sent to a server), and the
 * page unpacks it with the browser's DecompressionStream("deflate-raw").
 */
object ReportCodec {
    const val PAGE = "https://myklharu.github.io/vyrk-tv/report.html"

    fun encode(text: String): String {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(text.toByteArray(Charsets.UTF_8))
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decode(packed: String): String {
        val inf = Inflater(true)
        inf.setInput(Base64.getUrlDecoder().decode(packed))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toString("UTF-8")
    }

    fun link(text: String) = "$PAGE#r=${encode(text)}"

    /**
     * The report that fits a QR code: [header] whole, then as many of the most recent [log] lines
     * as keep the link within [maxChars] (a dense but scannable code), oldest first.
     */
    fun fit(header: String, log: List<String>, maxChars: Int = MAX_LINK): String {
        fun text(n: Int) = buildString {
            append(header.trimEnd()).append('\n')
            if (n < log.size) append("… ").append(log.size - n).append(" earlier lines left out\n")
            log.takeLast(n).forEach { append(it).append('\n') }
        }
        if (link(text(log.size)).length <= maxChars) return text(log.size)
        var lo = 0; var hi = log.size
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (link(text(mid)).length <= maxChars) lo = mid else hi = mid - 1
        }
        return text(lo)
    }

    /** About QR version 30 at error correction L: scans from a phone held near the TV. */
    const val MAX_LINK = 1800
}
