package com.vyrk.tv.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.MetaPreview
import com.vyrk.tv.data.Profile
import com.vyrk.tv.data.Tab

sealed interface Screen {
    data object Loading : Screen
    data object Welcome : Screen
    data class Pairing(val profileId: String?) : Screen
    data object Main : Screen
}

enum class Section { TABS, SEARCH, SETTINGS }

/** Where an overlay was opened from, so focus can return to that exact poster. */
data class Origin(val tab: String, val rowKey: String, val index: Int, val itemId: String)

sealed interface Overlay {
    data class Info(val meta: MetaPreview, val origin: Origin?, val focusResume: Boolean = false, val showSources: Boolean = false) : Overlay
    data class ItemMenu(val meta: MetaPreview, val origin: Origin?) : Overlay
    data class InstallConfirm(val descriptor: Descriptor?, val input: String, val external: Boolean, val error: String? = null) : Overlay
    data class AddonQr(val descriptor: Descriptor) : Overlay
    data class Message(val title: String, val body: String) : Overlay
    data class SeeAll(val title: String, val ref: com.vyrk.core.catalog.CatalogRef) : Overlay
    data class Person(val id: Long, val name: String) : Overlay
    /** The avatar's menu in the top bar: switch profile, Settings. */
}

@Stable
class AppState {
    var screen by mutableStateOf<Screen>(Screen.Loading)
    var section by mutableStateOf(Section.TABS)
    var tab by mutableStateOf(Tab.HOME)
    val overlays = mutableStateListOf<Overlay>()
    var toast by mutableStateOf<Toast?>(null)
    /** A focus request to restore after an overlay or playback closes. */
    var restore by mutableStateOf<Origin?>(null)
    /** Bumped when focus should go back to the tab strip. */
    var focusTabsTick by mutableStateOf(0)
    /** Bumped when the hero's Watch now should take focus (e.g. Continue Watching emptied). */
    var focusHeroTick by mutableStateOf(0)
    /** Non-focusable trailer preview card; any focus move or Back closes it. */
    var preview by mutableStateOf<MetaPreview?>(null)
    var searchQuery by mutableStateOf("")
    /** Backdrop of the title last featured on a catalogue page; Settings shows it blurred behind its glass panels. */
    var backdrop by mutableStateOf<String?>(null)
    /** True while the top navigation bar holds focus; pages never steal it then. */
    var navFocused by mutableStateOf(false)
    var pendingLink: String? = null
    var pendingLinkExternal: Boolean = true

    data class Toast(val text: String, val id: Long = System.nanoTime())

    fun push(o: Overlay) { overlays.add(o) }
    fun pop(): Overlay? = if (overlays.isEmpty()) null else overlays.removeAt(overlays.lastIndex)
    fun replaceTop(o: Overlay) { if (overlays.isNotEmpty()) overlays[overlays.lastIndex] = o else overlays.add(o) }
    fun showToast(text: String) { toast = Toast(text) }
}
