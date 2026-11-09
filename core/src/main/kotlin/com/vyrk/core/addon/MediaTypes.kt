package com.vyrk.core.addon

/** Both Stremio live catalogue types have no VOD resume or next episode. */
fun isLiveType(type: String): Boolean = type == "tv" || type == "channel"
