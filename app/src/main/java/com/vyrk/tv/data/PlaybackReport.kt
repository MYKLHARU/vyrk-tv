package com.vyrk.tv.data

import android.os.Build
import com.vyrk.core.diag.ReportCodec
import com.vyrk.tv.BuildConfig
import com.vyrk.tv.player.DvDecoders
import com.vyrk.tv.player.MpvPlayer

/**
 * "Send a playback report": what this device can play, the settings that change playback and
 * VYRK's recent log (already free of links and tokens, see [Diagnostics]). No account details,
 * no add-on addresses. It goes nowhere by itself: the TV shows it as a QR code that opens the
 * report page on the phone (the report rides in the link's # part, which browsers never send
 * to a server); the phone app shares it.
 */
object PlaybackReport {
    private fun yn(v: Boolean?) = when (v) { true -> "yes"; false -> "no"; null -> "?" }

    fun header(graph: AppGraph): String {
        val r = graph.device.report
        val d = graph.deviceSettings.value
        return buildString {
            appendLine("VYRK ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) playback report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("Display: ${r.displayWidth}x${r.displayHeight} · HDR10 ${yn(r.hdr10)} · HDR10+ ${yn(r.hdr10Plus)} · Dolby Vision ${yn(r.dolbyVision)} · HLG ${yn(r.hlg)}")
            appendLine("Video decoders: " + r.decoders.entries.joinToString(" · ") { (codec, h) -> "${codec.name} ${h?.let { "${it}p" } ?: "?"}" } +
                " · HEVC 10-bit ${yn(r.hevc10)} · AV1 ${yn(r.av1)}")
            appendLine("Dolby Vision decoder profiles: ${DvDecoders.profiles.sorted().joinToString(",").ifEmpty { "none" }} · profile 7 plays as ${DvDecoders.mode7(r.dolbyVision, d.dolbyVisionAsHdr10).name}")
            appendLine("Audio out: ${r.audioEncodings.joinToString(",").ifEmpty { "PCM" }} · ${r.maxChannels} ch")
            appendLine("Settings: backup player ${d.backupPlayer.name}${if (MpvPlayer.supported) "" else " (needs Android 7)"} · DV as HDR10 ${yn(d.dolbyVisionAsHdr10)} · " +
                "passthrough ${yn(d.passthrough)} · software audio ${yn(d.softwareAudio)} · frame rate ${yn(d.frameRateMatching)} · instant start ${yn(d.instantStart)} · " +
                "debrid ${graph.debrid.service?.label ?: "none"}")
            append("Log (newest last):")
        }
    }

    /** The report as text, trimmed to what fits a QR code. */
    fun text(graph: AppGraph): String = ReportCodec.fit(header(graph), Diagnostics.dump())

    /** The report page's link with this report in it. */
    fun link(graph: AppGraph): String = ReportCodec.link(text(graph))
}
