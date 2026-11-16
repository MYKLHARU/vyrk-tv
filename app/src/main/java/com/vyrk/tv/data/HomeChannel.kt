package com.vyrk.tv.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.vyrk.core.addon.MetaPreview
import com.vyrk.tv.MainActivity
import com.vyrk.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VYRK's own row on the launcher's home screen ("preview channel"): titles picked for you, each
 * opening its page in VYRK. Launchers that show app rows (Android TV Home) list it; Google TV
 * shows only Watch Next ([WatchNext]). The channel is created once and its titles replaced on
 * each publish.
 */
class HomeChannel(private val context: Context) {
    private val supported = Build.VERSION.SDK_INT >= 26
    private val prefs = context.getSharedPreferences("vyrk_home_channel", Context.MODE_PRIVATE)

    @SuppressLint("RestrictedApi")
    suspend fun publish(items: List<MetaPreview>) = withContext(Dispatchers.IO) {
        if (!supported || items.isEmpty()) return@withContext
        runCatching {
            val id = channelId() ?: return@runCatching
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
            items.take(MAX).forEachIndexed { i, m ->
                val p = PreviewProgram.Builder()
                    .setChannelId(id)
                    .setType(if (m.type == "series") TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setTitle(m.name)
                    .setInternalProviderId(m.id)
                    .setWeight(MAX - i)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                    .setIntentUri(Uri.parse("vyrk://open?type=${Uri.encode(m.type)}&id=${Uri.encode(m.id)}"))
                m.poster?.let { p.setPosterArtUri(Uri.parse(it)) }
                m.description?.let { p.setDescription(it.take(240)) }
                m.releaseInfo?.let { p.setReleaseDate(it.take(4)) }
                context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, p.build().toContentValues())
            }
        }.onFailure { Diagnostics.log("homechannel", "publish failed: ${it.javaClass.simpleName}") }
    }

    /** The channel's id, creating it (with VYRK's mark as its logo) the first time. */
    @SuppressLint("RestrictedApi")
    private fun channelId(): Long? {
        val saved = prefs.getLong(KEY, -1L)
        if (saved >= 0 && exists(saved)) return saved
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context, MainActivity::class.java))
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(R.string.home_channel_name))
            .setAppLinkIntent(launch)
            .setInternalProviderId("vyrk-for-you")
            .build()
        val uri = context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()) ?: return null
        val id = ContentUris.parseId(uri)
        prefs.edit().putLong(KEY, id).apply()
        logo()?.let { ChannelLogoUtils.storeChannelLogo(context, id, it) }
        // The app's first channel is shown by the launcher; asking keeps it shown where allowed.
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        return id
    }

    private fun exists(id: Long): Boolean = runCatching {
        context.contentResolver.query(TvContractCompat.buildChannelUri(id), null, null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun logo(): Bitmap? = runCatching {
        val d = ContextCompat.getDrawable(context, R.drawable.ic_launcher) ?: return null
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 160, 160); d.draw(Canvas(b)); b
    }.getOrNull()

    private companion object {
        const val KEY = "channel_id"
        const val MAX = 20
    }
}
