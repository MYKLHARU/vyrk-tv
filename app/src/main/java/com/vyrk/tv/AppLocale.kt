package com.vyrk.tv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences("locale", Context.MODE_PRIVATE).getString("lang", "").orEmpty()
        if (lang.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(cfg)
    }
}
