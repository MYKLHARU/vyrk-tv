package com.vyrk.tv.player

import android.content.Intent
import com.vyrk.core.addon.MetaPreview
import com.vyrk.core.stream.Ranked
import com.vyrk.tv.MainActivity
import com.vyrk.tv.data.AppGraph
import com.vyrk.tv.data.ProfileSession
import com.vyrk.tv.ui.Origin

/** Everything PlayerActivity needs; handed over in memory (same process). */
data class PlaybackRequest(
    val meta: MetaPreview,
    /** Episode or movie video ID; null means "resume point or first episode". */
    val videoId: String?,
    /** Set when the user chose a source by hand: failures ask instead of switching. */
    val picked: Ranked? = null,
    /** Pre-ranked candidates from the info page, to avoid a second fetch. */
    val ranked: List<Ranked>? = null,
    val origin: Origin? = null,
)

object Playback {
    fun start(
        activity: MainActivity,
        graph: AppGraph,
        session: ProfileSession,
        meta: MetaPreview,
        videoId: String? = null,
        picked: Ranked? = null,
        ranked: List<Ranked>? = null,
        origin: Origin? = null,
    ) {
        val vid = videoId ?: when {
            meta.type == "series" -> session.item(meta.id)?.takeIf { it.isInContinueWatching }?.state?.videoId
            else -> meta.behaviorHints.defaultVideoId ?: meta.id
        }
        graph.pendingPlayback = PlaybackRequest(meta, vid, picked, ranked, origin)
        activity.startActivity(Intent(activity, PlayerActivity::class.java))
    }
}
