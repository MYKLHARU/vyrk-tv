package com.vyrk.tv.data
import com.vyrk.core.addon.AddonClient
import com.vyrk.core.net.HttpTransport
import com.vyrk.core.stream.DeviceCaps
import com.vyrk.tv.ui.browse.MetaCache
import kotlinx.coroutines.flow.MutableStateFlow
class AppGraph(val transport: HttpTransport) {
    val tmdb = com.vyrk.core.tmdb.TmdbClient(transport, "")
    val addonClient = AddonClient(transport)
    val metaCache = MetaCache(addonClient)
    val animeIds = com.vyrk.core.anime.AnimeIds(transport)
    /** No bundled anime IDs in these tests. */
    val animeArtIds = com.vyrk.core.anime.AnimeArtIds.EMPTY
    val deviceSettings = MutableStateFlow(DeviceSettings())
    fun updateDevice(f: (DeviceSettings) -> DeviceSettings) { deviceSettings.value = f(deviceSettings.value) }
    val device = AuditDevice()
    fun playbackCaps() = device.caps(deviceSettings.value.measuredBps)
    val debrid = AuditDebrid()
    suspend fun rankStreams(
        results: List<Pair<com.vyrk.core.addon.Descriptor, com.vyrk.core.addon.AddonResult<com.vyrk.core.addon.StreamsResponse>>>,
        prefs: com.vyrk.core.stream.RankPrefs,
    ) = com.vyrk.core.stream.StreamRanker.rank(com.vyrk.core.stream.StreamRanker.candidates(results), playbackCaps(), prefs)
}
/** No debrid service in these tests: torrents stay excluded. */
class AuditDebrid {
    val service: com.vyrk.core.debrid.DebridService? = null
    suspend fun resolve(stream: com.vyrk.core.addon.Stream, videoId: String?): com.vyrk.core.debrid.DebridResult = com.vyrk.core.debrid.DebridResult.SignedOut
    fun forget(stream: com.vyrk.core.addon.Stream, videoId: String?) {}
}
class AuditDevice {
    fun caps(speed: Double?) = DeviceCaps(measuredBps = speed)
    fun recordDecoderFailure(group: String) {}
}
