package com.vyrk.tv.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Scrolls only when the focused item would leave a comfort zone (12% leading, 10% trailing),
 * instead of the default 30% pivot. Used as is by horizontal rows.
 */
@OptIn(ExperimentalFoundationApi::class)
object ComfortZoneSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lead = containerSize * 0.12f
        val trail = containerSize * 0.90f
        val end = offset + size
        return when {
            size > trail - lead -> offset - lead
            offset < lead -> offset - lead
            end > trail -> end - trail
            else -> 0f
        }
    }
}

/**
 * Flags read by [PageSpec] during a focus change. They are atomics, not snapshot state,
 * because the spec runs before recomposition.
 */
class PageFocusFlags {
    val heroFocused = AtomicBoolean(false)
    val top10CardFocused = AtomicBoolean(false)
    /** Distance from the list's top to where the focused poster's top should sit. */
    val posterTarget = AtomicReference(0f)
    /** True when the next bring-into-view should keep the page at the top. */
    val keepTop = AtomicBoolean(false)
}

/**
 * Page scrolling: the hero and the Top 10 card keep the page at the top (the caller scrolls to
 * item 0); otherwise the focused row sits just below the title header.
 */
@OptIn(ExperimentalFoundationApi::class)
class PageSpec(private val flags: PageFocusFlags) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (flags.heroFocused.get()) return 0f
        if (flags.top10CardFocused.get()) {
            // Stay at the top while the focused button fits on screen.
            return if (offset + size <= containerSize) 0f else offset + size - containerSize
        }
        val target = flags.posterTarget.get()
        return offset - target
    }
}
