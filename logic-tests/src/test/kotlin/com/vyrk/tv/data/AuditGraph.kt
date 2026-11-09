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
    val deviceSettings = MutableStateFlow(DeviceSettings())
    val device = AuditDevice()
}
class AuditDevice {
    fun caps(speed: Double?) = DeviceCaps(measuredBps = speed)
    fun recordDecoderFailure(group: String) {}
}
