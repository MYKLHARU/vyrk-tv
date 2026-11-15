package com.vyrk.tv.ui.phone

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Which interface VYRK shows: the remote-driven TV interface, or the touch interface for phones
 * and tablets. A device is a TV when Android says so (television UI mode, which Android TV,
 * Google TV and Fire TV report) or when it has no touchscreen at all (TV boxes that don't report
 * the television mode). Everything else with a touchscreen gets the touch interface.
 */
object FormFactor {
    @Volatile private var cached: Boolean? = null

    fun isTv(context: Context): Boolean = cached ?: detect(context.applicationContext).also { cached = it }

    fun isPhone(context: Context): Boolean = !isTv(context)

    private fun detect(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) && !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
