package com.vyrk.tv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Other video players on this device, for "Play with …": VLC, Just Player, MX Player, mpv and any
 * other app that opens videos. VYRK hands over the link, the title and where to start, and reads
 * back where you stopped when the player reports it.
 */
object ExternalPlayers {
    data class App(val pkg: String, val label: String)

    private val KNOWN = listOf(
        "org.videolan.vlc" to "VLC",
        "com.brouken.player" to "Just Player",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "is.xyz.mpv" to "mpv",
        "org.xbmc.kodi" to "Kodi",
    ).toMap()

    /** Installed apps that open videos, the well-known players first; never VYRK itself. */
    fun installed(context: Context): List<App> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/video.mkv"), "video/*")
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val found = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            .mapNotNull { ri -> ri.activityInfo?.packageName?.takeIf { it != context.packageName }?.let { it to (KNOWN[it] ?: ri.loadLabel(pm).toString()) } }
            .distinctBy { it.first }
        return found.sortedBy { (pkg, _) -> KNOWN.keys.indexOf(pkg).let { if (it < 0) Int.MAX_VALUE else it } }.map { App(it.first, it.second) }
    }

    fun label(context: Context, pkg: String): String =
        KNOWN[pkg] ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** The intent to play [url] in [pkg] (null: let Android ask which app) from [positionMs]. */
    fun intent(pkg: String?, url: String, title: String, positionMs: Long, headers: Map<String, String> = emptyMap()): Intent {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .putExtra("return_result", true)
            .putExtra("secure_uri", true)
        if (pkg != null) i.setPackage(pkg)
        when (pkg) {
            // VLC reads a long, and starts from the beginning unless told otherwise.
            "org.videolan.vlc" -> i.putExtra("position", positionMs).putExtra("from_start", positionMs <= 0)
            else -> i.putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        if (headers.isNotEmpty() && pkg?.startsWith("com.mxtech.") == true) {
            i.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        }
        return i
    }

    /** Where playback stopped, as players report it: (position, duration) in ms, or null. */
    fun result(data: Intent?): Pair<Long, Long>? {
        val e = data?.extras ?: return null
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            when (val v = e.get(k)) { is Long -> v; is Int -> v.toLong(); is Double -> v.toLong(); is Float -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
        }
        val pos = num("extra_position", "position") ?: return null
        val dur = num("extra_duration", "duration") ?: 0L
        return pos to dur
    }

    fun launch(context: android.app.Activity, intent: Intent, requestCode: Int): Boolean = try {
        @Suppress("DEPRECATION")
        context.startActivityForResult(if (intent.`package` == null) Intent.createChooser(intent, null) else intent, requestCode)
        true
    } catch (_: ActivityNotFoundException) { false }
}
