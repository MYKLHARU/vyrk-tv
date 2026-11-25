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
    /** What Play plays when no episode was chosen: a series' resume point (null: the player takes the first episode), or the film. */
    fun videoFor(session: ProfileSession, meta: MetaPreview): String? = when {
        meta.type == "series" -> session.item(meta.id)?.takeIf { it.isInContinueWatching }?.state?.videoId
        else -> meta.behaviorHints.defaultVideoId ?: meta.id
    }

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
        val vid = videoId ?: videoFor(session, meta)
        graph.instantStart.cancel()
        // Sources instant start already ranked for this video, so the player doesn't ask the add-ons again.
        val handOff = ranked ?: if (picked == null) graph.instantStart.rankedFor(meta.id, vid) else null
        graph.pendingPlayback = PlaybackRequest(meta, vid, picked, handOff, origin)
        activity.startActivity(Intent(activity, PlayerActivity::class.java))
    }
}
