package com.vyrk.tv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import com.vyrk.tv.R

/**
 * Soft clicks for moving, selecting and going back with the remote (Settings › Picture & sound,
 * off by default). Short tones made for VYRK, played quietly through the UI sound stream.
 */
object NavSounds {
    @Volatile var enabled = false
    private var pool: SoundPool? = null
    private var move = 0; private var select = 0; private var back = 0

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        move = p.load(context, R.raw.nav_move, 1)
        select = p.load(context, R.raw.nav_select, 1)
        back = p.load(context, R.raw.nav_back, 1)
        pool = p
    }

    fun onKey(e: KeyEvent) {
        if (!enabled) return
        val p = pool ?: return
        val id = when {
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && e.keyCode in MOVES -> move
            e.action == KeyEvent.ACTION_UP && !e.isCanceled && e.keyCode in SELECTS -> select
            e.action == KeyEvent.ACTION_UP && !e.isCanceled && e.keyCode == KeyEvent.KEYCODE_BACK -> back
            else -> return
        }
        p.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    private const val VOLUME = 0.35f
    private val MOVES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    private val SELECTS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
}
