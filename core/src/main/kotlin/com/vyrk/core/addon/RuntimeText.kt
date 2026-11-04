package com.vyrk.core.addon

/**
 * Add-ons write runtimes many ways ("95 min", "1.6h", "2h 10min", "120"). These read them as
 * minutes and show them one way: "1h 35m", "2h", "45m". Anything else is shown as given.
 */
object RuntimeText {
    private val minutesOnly = Regex("""^\s*(\d{1,4})\s*(?:m|min|mins|minutes?)?\.?\s*$""", RegexOption.IGNORE_CASE)
    private val hoursOnly = Regex("""^\s*(\d{1,2}(?:[.,]\d+)?)\s*(?:h|hr|hrs|hours?)\.?\s*$""", RegexOption.IGNORE_CASE)
    private val hoursMinutes = Regex("""^\s*(\d{1,2})\s*(?:h|hr|hrs|hours?)\s*(\d{1,2})\s*(?:m|min|mins|minutes?)?\.?\s*$""", RegexOption.IGNORE_CASE)

    /** Runtime in minutes, or null when [raw] isn't a runtime this understands. */
    fun minutes(raw: String?): Int? {
        val s = raw?.takeIf { it.isNotBlank() } ?: return null
        hoursMinutes.matchEntire(s)?.let { m -> return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
        hoursOnly.matchEntire(s)?.let { m -> return m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { (it * 60).toInt() } }
        minutesOnly.matchEntire(s)?.let { m -> return m.groupValues[1].toInt().takeIf { it > 0 } }
        return null
    }

    /** "1h 35m", "2h" or "45m"; [raw] unchanged if it isn't a runtime; null if blank. */
    fun format(raw: String?): String? {
        val m = minutes(raw) ?: return raw?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}
