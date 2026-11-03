package com.vyrk.core.addon

/**
 * URL building for the Stremio add-on HTTP transport, ported from stremio-core
 * (src/addon_transport/http_transport/http_transport.rs and src/types/query_params_encode.rs).
 *
 * Every path component is percent-encoded with the JavaScript `encodeURIComponent` set,
 * so an ID such as `kitsu:1376` is sent as `kitsu%3A1376`. IDs are never rewritten.
 */
object AddonUrls {
    const val MANIFEST_PATH = "/manifest.json"

    private const val UNRESERVED = "-_.!~*'()"

    /** Equivalent of stremio-core URI_COMPONENT_ENCODE_SET applied with utf8_percent_encode. */
    fun encodeComponent(value: String): String {
        val bytes = value.encodeToByteArray()
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || UNRESERVED.indexOf(ch) >= 0)) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun encodeExtra(extra: List<Pair<String, String>>): String =
        extra.joinToString("&") { (k, v) -> "${encodeComponent(k)}=${encodeComponent(v)}" }

    /**
     * Builds `{base}/{resource}/{type}/{id}[/{extra}].json` from a transport URL that ends in
     * `/manifest.json`. Query strings on the transport URL are kept after the resource path,
     * exactly as stremio-core's string replace does.
     */
    fun resourceUrl(
        transportUrl: String,
        resource: String,
        type: String,
        id: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): String {
        require(isHttpTransport(transportUrl)) { "add-on transport URL must end with $MANIFEST_PATH" }
        val path = buildString {
            append('/').append(encodeComponent(resource))
            append('/').append(encodeComponent(type))
            append('/').append(encodeComponent(id))
            if (extra.isNotEmpty()) append('/').append(encodeExtra(extra))
            append(".json")
        }
        val idx = transportUrl.lastIndexOf(MANIFEST_PATH)
        return transportUrl.substring(0, idx) + path + transportUrl.substring(idx + MANIFEST_PATH.length)
    }

    fun isHttpTransport(url: String): Boolean {
        val noQuery = url.substringBefore('?').substringBefore('#')
        return (url.startsWith("http://") || url.startsWith("https://")) && noQuery.endsWith(MANIFEST_PATH)
    }

    /** Base URL for an add-on's configuration page (`…/configure`). */
    fun configureUrl(transportUrl: String): String? {
        if (!isHttpTransport(transportUrl)) return null
        val noQuery = transportUrl.substringBefore('?')
        return noQuery.removeSuffix(MANIFEST_PATH) + "/configure"
    }

    /**
     * Normalises user input and links into an HTTPS/HTTP manifest URL.
     * Accepts `stremio://host/path/manifest.json`, `vyrk://install?url=…` and plain URLs.
     * Returns null when the input can't be an add-on manifest.
     */
    fun normaliseInstallUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("vyrk://", ignoreCase = true) || s.startsWith("aurex://", ignoreCase = true)) {
            val q = s.substringAfter('?', "")
            val raw = q.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
            s = percentDecode(raw)
        }
        if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
        if (!s.contains("://")) s = "https://$s"
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return null
        val noQuery = s.substringBefore('?')
        if (!noQuery.endsWith(MANIFEST_PATH)) {
            s = noQuery.trimEnd('/') + MANIFEST_PATH + (if (s.contains('?')) "?" + s.substringAfter('?') else "")
        }
        return s
    }

    fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex); i += 3; continue
                }
            }
            if (c == '+') out.write(' '.code) else out.write(c.toString().encodeToByteArray())
            i++
        }
        return out.toByteArray().decodeToString()
    }
}

/** Redaction of secrets for display, logs and diagnostics. */
object Redact {
    /**
     * Shows only `scheme://host/…`: drops user-info, port-less path and query, which is where
     * add-ons keep API keys and debrid tokens.
     */
    fun url(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty()) return "…"
        val rest = url.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@')
        val hasMore = rest.length > authority.length
        return "$scheme://$host" + if (hasMore) "/…" else ""
    }

    private val urlPattern = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>]+""")
    private val keyPattern = Regex("""(?i)("?(authKey|auth_key|token|password|apikey|api_key)"?\s*[:=]\s*"?)([^"&\s,}]+)""")

    /** Scrubs URLs and token-like values from free text such as error messages. */
    fun text(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val noUrls = urlPattern.replace(text) { url(it.value) }
        return keyPattern.replace(noUrls) { it.groupValues[1] + "‹redacted›" }
    }
}
