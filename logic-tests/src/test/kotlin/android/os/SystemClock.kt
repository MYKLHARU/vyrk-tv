package android.os
object SystemClock {
    /** Tests move the clock forward (never back) instead of waiting. */
    @Volatile var advanceMs = 0L
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 + advanceMs
}
