package com.vyrk.tv.player
import com.vyrk.core.addon.*
import com.vyrk.core.playback.*
import com.vyrk.core.stream.Ranked
data class PlaybackRequest(val meta: MetaPreview, val videoId: String?, val picked: Ranked? = null, val ranked: List<Ranked>? = null, val origin: Any? = null)
class AuditPlaybackParameters(val speed: Float = 1f)
class AuditPlayer {
    val playbackParameters = AuditPlaybackParameters()
    var currentPosition = 0L
    var duration = 45 * 60_000L
    var bufferedPosition = 0L
    var isPlaying = true
    var playWhenReady = true
    fun pause() { isPlaying = false }
    fun play() { isPlaying = true }
    fun prepare() {}
    fun seekTo(position: Long) { currentPosition = position }
    fun seekToDefaultPosition() { currentPosition = 0 }
}
class PlayerEngine {
    val player = AuditPlayer()
    val preparations = mutableListOf<Pair<Stream, Long>>()
    var audio = listOf(AudioTrack("0:0", "eng", "English", isDefault = true))
    var texts = emptyList<TextTrack>()
    val selections = mutableListOf<Pair<Int, String?>>()
    var preservedAudio = false
    fun prepare(stream: Stream, startMs: Long, preserveAudio: Boolean = false) { preservedAudio = preserveAudio; preparations += stream to startMs; player.currentPosition = startMs; player.isPlaying = true }
    fun audioTracks() = audio
    fun embeddedTextTracks() = texts
    fun select(type: Int, id: String?) { selections += type to id }
    fun classify(error: androidx.media3.common.PlaybackException) = error.errorClass
}
