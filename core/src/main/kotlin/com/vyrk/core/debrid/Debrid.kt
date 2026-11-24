package com.vyrk.core.debrid

import com.vyrk.core.addon.Stream
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** The debrid services VYRK can sign in to, to play torrent sources as ordinary web links. */
@Serializable
enum class DebridService(val label: String, val short: String) {
    REAL_DEBRID("Real-Debrid", "RD"),
    TORBOX("TorBox", "TB"),
}

/** A sign-in, stored encrypted on the device. Real-Debrid uses all fields; TorBox only [token] (its API key). */
@Serializable
data class DebridLogin(
    val service: DebridService,
    val token: String,
    val refreshToken: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val expiresAtMs: Long = 0,
    val username: String? = null,
)

/** A device sign-in in progress: the code to enter at [verificationUrl]; [qrUrl] opens the page with the code filled in. */
data class DebridDeviceCode(
    val deviceCode: String, val userCode: String, val verificationUrl: String, val qrUrl: String, val expiresInSec: Int, val intervalSec: Int,
)

/** The account behind a sign-in, for Settings. */
data class DebridUser(val name: String?, val premium: Boolean, val premiumUntil: String? = null)

/** One file inside a torrent as the service lists it. */
data class DebridFile(val id: Int, val path: String, val bytes: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** What the add-on said about the file it meant. */
data class FileHint(val filename: String? = null, val fileIdx: Int? = null, val season: Int? = null, val episode: Int? = null) {
    companion object {
        /** From a stream and the video being played (IMDb "tt123:1:2" is season 1, episode 2). */
        fun of(stream: Stream, videoId: String?): FileHint {
            val parts = videoId?.split(':').orEmpty()
            val episodic = parts.size == 3 && parts[0].startsWith("tt")
            return FileHint(stream.behaviorHints.filename, stream.fileIdx,
                parts.getOrNull(1)?.toIntOrNull()?.takeIf { episodic }, parts.getOrNull(2)?.toIntOrNull()?.takeIf { episodic })
        }
    }
}

sealed interface DebridResult {
    data class Ok(val url: String, val filename: String?, val bytes: Long?) : DebridResult
    /** The service doesn't have the torrent ready; VYRK doesn't wait for downloads. */
    data object NotCached : DebridResult
    /** No video in the torrent matches what's being played. */
    data object NoFile : DebridResult
    /** The service refuses this file (it was taken down). */
    data object Removed : DebridResult
    /** The sign-in was rejected: connect again in Settings. */
    data object SignedOut : DebridResult
    data object NotPremium : DebridResult
    data class Failed(val reason: String) : DebridResult
}

object DebridFiles {
    private val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "mpg", "mpeg", "flv", "vob", "ogm", "3gp")
    private val SXXEYY = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?![0-9])""")
    private val NXNN = Regex("""(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val EXTRAS = Regex("""(?i)(?:^|/)(?:samples?|extras|featurettes|behind the scenes|deleted scenes|trailers?)/""")
    private val SAMPLE = Regex("""(?i)(?:^|[^a-z])sample(?:[^a-z]|$)""")

    fun isVideo(path: String) = path.substringAfterLast('.', "").lowercase() in VIDEO

    /** Samples and bonus material, which are never the film. */
    fun isExtra(f: DebridFile) = EXTRAS.containsMatchIn(f.path) || (SAMPLE.containsMatchIn(f.name) && f.bytes < 500_000_000)

    /** The season and episode in a file name, when it has one. */
    fun episodeOf(name: String): Pair<Int, Int>? =
        (SXXEYY.find(name) ?: NXNN.find(name))?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /**
     * The file to play: the add-on's file name, else its file index (when that file is the right
     * episode), else the episode by name, else the largest video. In a pack with several videos
     * and none for the episode, nothing (rather than the wrong episode). [index] gives a file's
     * position in the torrent, Stremio's fileIdx.
     */
    fun pick(files: List<DebridFile>, hint: FileHint, index: (DebridFile) -> Int?): DebridFile? {
        val videos = files.filter { isVideo(it.path) }
        if (videos.isEmpty()) return null
        val main = videos.filterNot { isExtra(it) }.ifEmpty { videos }
        hint.filename?.let { n -> videos.firstOrNull { it.name.equals(n.substringAfterLast('/'), ignoreCase = true) }?.let { return it } }
        val ep = if (hint.season != null && hint.episode != null) hint.season to hint.episode else null
        hint.fileIdx?.let { idx ->
            val f = videos.firstOrNull { index(it) == idx }
            if (f != null && (ep == null || episodeOf(f.name).let { it == null || it == ep })) return f
        }
        if (ep != null) {
            val matches = main.filter { episodeOf(it.name) == ep }
            if (matches.isNotEmpty()) return matches.maxBy { it.bytes }
            return main.singleOrNull()
        }
        return main.maxBy { it.bytes }
    }
}

object Magnet {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A magnet link from a hash and Stremio's sources ("tracker:udp://…"). */
    fun of(infoHash: String, sources: List<String> = emptyList(), name: String? = null): String = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash.lowercase())
        name?.takeIf { it.isNotBlank() }?.let { append("&dn=").append(enc(it)) }
        sources.filter { it.startsWith("tracker:") }.take(20).forEach { append("&tr=").append(enc(it.removePrefix("tracker:"))) }
    }
}
