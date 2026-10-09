package com.tobevpn.app.util

import android.content.Context
import android.text.format.DateFormat
import com.tobevpn.app.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * The next traffic limit reset (current-plan next_traffic_reset_at), worded
 * the same on Home, in the subscription sheet and in traffic alerts. Mirrors
 * the desktop client's trafficReset.ts. Times are shown in the device's time
 * zone.
 */
data class TrafficResetText(
    /** "03.11.2026" / "завтра" / "в 05:10": under the traffic bar on Home. */
    val whenText: String,
    /** "Сброс трафика 03.11 в 05:10": traffic alerts. */
    val full: String,
    /** "Сброс 03.11 в 05:10": chip in the plan card without the limits block. */
    val chip: String,
    /** "03.11 в 05:10": pill under the traffic limit in the plan card. */
    val dateTime: String,
)

/** Null when there is no limit, no reset date, or the date is already past. */
fun trafficResetText(
    context: Context,
    resetAt: Long?,
    limitBytes: Long,
    now: Long = System.currentTimeMillis(),
): TrafficResetText? {
    if (resetAt == null || limitBytes <= 0L || resetAt <= now) return null
    val locale = context.resources.configuration.locales[0]
    val russian = locale.language == "ru"
    val date = Date(resetAt)
    val time = SimpleDateFormat(
        if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
        locale,
    ).format(date)
    val shortDate = SimpleDateFormat(if (russian) "dd.MM" else "MMM d", locale).format(date)
    // Home shows the year, like the plan expiry "до 01.06.2027".
    val dateWithYear = SimpleDateFormat(if (russian) "dd.MM.yyyy" else "MMM d, yyyy", locale).format(date)

    val days = calendarDaysBetween(now, resetAt)
    val whenText = when {
        days <= 0L -> context.getString(R.string.traffic_reset_when_today, time)
        days == 1L -> context.getString(R.string.traffic_reset_when_tomorrow)
        else -> dateWithYear
    }
    return TrafficResetText(
        whenText = whenText,
        full = context.getString(R.string.traffic_reset_full, shortDate, time),
        chip = context.getString(R.string.traffic_reset_chip, shortDate, time),
        dateTime = context.getString(R.string.traffic_reset_datetime, shortDate, time),
    )
}

private const val DAY_MS = 86_400_000.0

/** Calendar days from [from] to [to] in the device time zone (0 = same day). */
internal fun calendarDaysBetween(from: Long, to: Long): Long =
    // Rounded: a day across a DST change is 23 or 25 hours long.
    Math.round((startOfDay(to) - startOfDay(from)) / DAY_MS)

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
